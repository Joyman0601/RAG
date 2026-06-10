package com.yhl.rag.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.yhl.rag.cost.CostGovernanceService;
import com.yhl.rag.cost.ModelTier;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.observability.MetricsService;
import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RagAskService {

    private static final Logger log = LoggerFactory.getLogger(RagAskService.class);
    private static final String NO_ANSWER = "根据现有资料无法回答。";
    private static final String SYSTEM_PROMPT = """
            你是一个严谨的 RAG 问答助手。
            你必须只基于用户提供的资料回答问题。
            如果资料中没有答案，回答“根据现有资料无法回答。”
            回答要简洁，不要编造资料外的信息。
            """;

    private final RagSearchService ragSearchService;
    private final LlmClient llmClient;
    private final RagProperties ragProperties;
    private final LlmProperties llmProperties;
    private final CostGovernanceService costGovernanceService;
    private final MockCurrentUserProvider currentUserProvider;
    private final MetricsService metricsService;
    private final QueryRewriterService queryRewriterService;

    @Autowired
    public RagAskService(
            RagSearchService ragSearchService,
            LlmClient llmClient,
            RagProperties ragProperties,
            LlmProperties llmProperties,
            CostGovernanceService costGovernanceService,
            MockCurrentUserProvider currentUserProvider,
            MetricsService metricsService,
            QueryRewriterService queryRewriterService
    ) {
        this.ragSearchService = ragSearchService;
        this.llmClient = llmClient;
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
        this.costGovernanceService = costGovernanceService;
        this.currentUserProvider = currentUserProvider;
        this.metricsService = metricsService;
        this.queryRewriterService = queryRewriterService;
    }

    public RagAskService(
            RagSearchService ragSearchService,
            LlmClient llmClient,
            RagProperties ragProperties,
            LlmProperties llmProperties,
            CostGovernanceService costGovernanceService,
            MockCurrentUserProvider currentUserProvider
    ) {
        this(
                ragSearchService,
                llmClient,
                ragProperties,
                llmProperties,
                costGovernanceService,
                currentUserProvider,
                new MetricsService(),
                new QueryRewriterService(llmClient, ragProperties)
        );
    }

    public RagAskResponse ask(String question) {
        return ask(question, false);
    }

    public RagAskResponse ask(String question, boolean debugRequested) {
        String requestId = UUID.randomUUID().toString();
        long startNanos = System.nanoTime();
        int questionLength = question == null ? 0 : question.length();
        int topK = ragProperties.getSearch().getTopK();
        double scoreThreshold = ragProperties.getSearch().getScoreThreshold();
        boolean debugEnabled = debugRequested && ragProperties.isDebugEnabled();
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        costGovernanceService.checkBeforeLlm(
                currentUser.getTenantId(),
                currentUser.getUserId(),
                "RAG_ASK",
                SYSTEM_PROMPT,
                List.of(new LlmMessage("user", question)),
                costGovernanceService.properties().getChatMaxInputTokens(),
                costGovernanceService.properties().getRagMaxOutputTokens()
        );

        String retrievalQuery = queryRewriterService.rewrite(question);
        RagSearchOutcome searchOutcome = ragSearchService.searchWithMetrics(retrievalQuery);
        List<RagSearchResult> searchResults = searchOutcome.results();
        if (searchResults.isEmpty()) {
            metricsService.recordRagNoAnswer();
            logAsk(requestId, questionLength, topK, scoreThreshold, searchResults, 0, NO_ANSWER, searchOutcome, 0, null, startNanos);
            return new RagAskResponse(NO_ANSWER, List.of(), debugChunks(searchResults, Set.of(), debugEnabled), new RagTokenUsage(0, 0, 0));
        }

        int maxContextChars = effectiveMaxContextChars(questionLength);
        if (maxContextChars <= 0) {
            metricsService.recordRagNoAnswer();
            logAsk(requestId, questionLength, topK, scoreThreshold, searchResults, 0, NO_ANSWER, searchOutcome, 0, null, startNanos);
            return new RagAskResponse(NO_ANSWER, List.of(), debugChunks(searchResults, Set.of(), debugEnabled), new RagTokenUsage(0, 0, 0));
        }

        ContextBuildResult contextBuildResult = buildContext(searchResults, maxContextChars);
        log.info("rag_context contextTokenEstimate={} truncatedByBudget={} usedChunkIds={} usedDocumentIds={}",
                contextBuildResult.contextTokenEstimate(),
                contextBuildResult.truncatedByBudget(),
                contextBuildResult.sources().stream().map(RagSource::getChunkId).toList(),
                contextBuildResult.sources().stream().map(RagSource::getDocumentId).distinct().toList());
        if (contextBuildResult.sources().isEmpty()) {
            metricsService.recordRagNoAnswer();
            logAsk(requestId, questionLength, topK, scoreThreshold, searchResults, 0, NO_ANSWER, searchOutcome, 0, null, startNanos);
            return new RagAskResponse(NO_ANSWER, List.of(), debugChunks(searchResults, Set.of(), debugEnabled), new RagTokenUsage(0, 0, 0));
        }

        String prompt = """
                问题：
                %s

                资料：
                %s
                """.formatted(question, contextBuildResult.context());

        long chatStartNanos = System.nanoTime();
        List<LlmMessage> messages = List.of(new LlmMessage("user", prompt));
        costGovernanceService.checkBeforeLlm(
                currentUser.getTenantId(),
                currentUser.getUserId(),
                "RAG_ASK_LLM",
                SYSTEM_PROMPT,
                messages,
                costGovernanceService.properties().getChatMaxInputTokens() + costGovernanceService.properties().getRagMaxContextTokens(),
                costGovernanceService.properties().getRagMaxOutputTokens()
        );
        LlmGenerationResult generationResult = llmClient.generateWithUsage(SYSTEM_PROMPT, messages);
        long chatDurationMs = Duration.ofNanos(System.nanoTime() - chatStartNanos).toMillis();
        String answer = generationResult.getAnswer();
        costGovernanceService.recordUsage(
                requestId,
                currentUser.getTenantId(),
                currentUser.getUserId(),
                "RAG_ASK",
                ModelTier.STANDARD,
                SYSTEM_PROMPT,
                messages,
                generationResult,
                chatDurationMs,
                true
        );
        Set<String> includedChunkIds = contextBuildResult.sources().stream()
                .map(RagSource::getChunkId)
                .collect(Collectors.toSet());
        logAsk(
                requestId,
                questionLength,
                topK,
                scoreThreshold,
                searchResults,
                contextBuildResult.context().length(),
                answer,
                searchOutcome,
                chatDurationMs,
                generationResult,
                startNanos
        );
        return new RagAskResponse(
                answer,
                contextBuildResult.sources(),
                debugChunks(searchResults, includedChunkIds, debugEnabled),
                new RagTokenUsage(
                        generationResult.getPromptTokens(),
                        generationResult.getCompletionTokens(),
                        generationResult.getTotalTokens()
                )
        );
    }

    private int effectiveMaxContextChars(int questionLength) {
        if (ragProperties.getContext().getMaxChars() <= 0) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "rag.context.max-chars 必须大于 0");
        }
        int promptOverhead = 120;
        int llmInputBudget = llmProperties.getMaxInputChars() - questionLength - promptOverhead;
        return Math.min(ragProperties.getContext().getMaxChars(), Math.max(0, llmInputBudget));
    }

    private ContextBuildResult buildContext(List<RagSearchResult> searchResults, int maxChars) {
        StringBuilder context = new StringBuilder();
        List<RagSource> sources = new ArrayList<>();
        int usedContextTokens = 0;
        boolean truncatedByBudget = false;

        for (RagSearchResult result : searchResults.stream()
                .sorted(java.util.Comparator.comparingDouble(RagSearchResult::getScore).reversed())
                .toList()) {
            int index = sources.size() + 1;
            String section = """
                    [%d]
                    文件：%s
                    内容：%s

                    """.formatted(index, result.getFilename(), result.getContent());
            int sectionTokens = costGovernanceService.tokenEstimator().estimate(section);

            if (context.length() + section.length() > maxChars) {
                truncatedByBudget = true;
                break;
            }
            if (usedContextTokens + sectionTokens > costGovernanceService.properties().getRagMaxContextTokens()) {
                truncatedByBudget = true;
                break;
            }

            context.append(section);
            usedContextTokens += sectionTokens;
            sources.add(new RagSource(
                    result.getDocumentId(),
                    result.getFilename(),
                    result.getChunkId(),
                    result.getScore(),
                    preview(result.getContent())
            ));
        }

        return new ContextBuildResult(context.toString(), sources, usedContextTokens, truncatedByBudget);
    }

    private List<RagRetrievedChunk> debugChunks(
            List<RagSearchResult> searchResults,
            Set<String> includedChunkIds,
            boolean debugEnabled
    ) {
        if (!debugEnabled) {
            return null;
        }

        // TODO: Before exposing debug previews in production, filter chunks by document permissions.
        return searchResults.stream()
                .map(result -> new RagRetrievedChunk(
                        result.getChunkId(),
                        result.getDocumentId(),
                        result.getFilename(),
                        result.getChunkIndex(),
                        result.getScore(),
                        includedChunkIds.contains(result.getChunkId()),
                        preview(result.getContent())
                ))
                .toList();
    }

    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 100 ? content : content.substring(0, 100);
    }

    private void logAsk(
            String requestId,
            int questionLength,
            int topK,
            double scoreThreshold,
            List<RagSearchResult> searchResults,
            int contextChars,
            String answer,
            RagSearchOutcome searchOutcome,
            long chatDurationMs,
            LlmGenerationResult generationResult,
            long startNanos
    ) {
        List<String> matchedChunkIds = searchResults.stream()
                .map(RagSearchResult::getChunkId)
                .toList();
        List<Double> scores = searchResults.stream()
                .map(RagSearchResult::getScore)
                .toList();

        log.info("rag_ask requestId={} questionLength={} topK={} scoreThreshold={} matchedCount={} matchedChunkIds={} scores={} contextChars={} answerLength={} embeddingDurationMs={} searchDurationMs={} chatDurationMs={} totalDurationMs={} model={} promptTokens={} completionTokens={} totalTokens={}",
                requestId,
                questionLength,
                topK,
                scoreThreshold,
                searchResults.size(),
                matchedChunkIds,
                scores,
                contextChars,
                answer == null ? 0 : answer.length(),
                searchOutcome == null ? 0 : searchOutcome.embeddingDurationMs(),
                searchOutcome == null ? 0 : searchOutcome.searchDurationMs(),
                chatDurationMs,
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis(),
                llmProperties.getModel(),
                generationResult == null ? null : generationResult.getPromptTokens(),
                generationResult == null ? null : generationResult.getCompletionTokens(),
                generationResult == null ? null : generationResult.getTotalTokens());
    }

    private record ContextBuildResult(String context, List<RagSource> sources, int contextTokenEstimate, boolean truncatedByBudget) {
    }
}
