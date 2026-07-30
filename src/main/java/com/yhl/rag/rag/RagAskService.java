package com.yhl.rag.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.yhl.rag.chunk.InMemoryParentStore;
import com.yhl.rag.chunk.ParentBlock;
import com.yhl.rag.chunk.ParentStore;
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
    private final ParentStore parentStore;
    private final ConversationHistoryStore conversationHistoryStore;

    @Autowired
    public RagAskService(
            RagSearchService ragSearchService,
            LlmClient llmClient,
            RagProperties ragProperties,
            LlmProperties llmProperties,
            CostGovernanceService costGovernanceService,
            MockCurrentUserProvider currentUserProvider,
            MetricsService metricsService,
            QueryRewriterService queryRewriterService,
            ParentStore parentStore,
            ConversationHistoryStore conversationHistoryStore
    ) {
        this.ragSearchService = ragSearchService;
        this.llmClient = llmClient;
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
        this.costGovernanceService = costGovernanceService;
        this.currentUserProvider = currentUserProvider;
        this.metricsService = metricsService;
        this.queryRewriterService = queryRewriterService;
        this.parentStore = parentStore;
        this.conversationHistoryStore = conversationHistoryStore;
    }

    public RagAskService(
            RagSearchService ragSearchService,
            LlmClient llmClient,
            RagProperties ragProperties,
            LlmProperties llmProperties,
            CostGovernanceService costGovernanceService,
            MockCurrentUserProvider currentUserProvider,
            MetricsService metricsService,
            QueryRewriterService queryRewriterService,
            ParentStore parentStore
    ) {
        this(
                ragSearchService,
                llmClient,
                ragProperties,
                llmProperties,
                costGovernanceService,
                currentUserProvider,
                metricsService,
                queryRewriterService,
                parentStore,
                new ConversationHistoryStore()
        );
    }

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
        this(
                ragSearchService,
                llmClient,
                ragProperties,
                llmProperties,
                costGovernanceService,
                currentUserProvider,
                metricsService,
                queryRewriterService,
                new InMemoryParentStore(),
                new ConversationHistoryStore()
        );
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
        return ask(question, null, false, null);
    }

    public RagAskResponse ask(String question, boolean debugRequested) {
        return ask(question, null, debugRequested, null);
    }

    public RagAskResponse ask(String question, String conversationId, boolean debugRequested) {
        return ask(question, conversationId, debugRequested, null);
    }

    public RagAskResponse ask(String question, String conversationId, boolean debugRequested, RagProperties.RetrievalMode modeOverride) {
        String requestId = UUID.randomUUID().toString();
        long startNanos = System.nanoTime();
        int questionLength = question == null ? 0 : question.length();
        int topK = ragProperties.getSearch().getTopK();
        double scoreThreshold = ragProperties.getSearch().getScoreThreshold();
        boolean debugEnabled = debugRequested && ragProperties.isDebugEnabled();
        RagProperties.RetrievalMode effectiveMode = modeOverride != null ? modeOverride : ragProperties.getSearch().getMode();
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

        boolean conversational = ragProperties.getQueryRewrite().getConversation().isEnabled()
                && question != null
                && conversationId != null && !conversationId.isBlank();
        String retrievalQuery = conversational
                ? queryRewriterService.rewrite(question, conversationHistoryStore.get(currentUser.getUserId(), conversationId))
                : queryRewriterService.rewrite(question);
        RagSearchOutcome searchOutcome = ragSearchService.searchWithMetrics(retrievalQuery, currentUser, false, null, modeOverride);
        List<RagSearchResult> searchResults = searchOutcome.results();
        if (searchResults.isEmpty()) {
            metricsService.recordRagNoAnswer();
            logAsk(requestId, questionLength, topK, scoreThreshold, searchResults, 0, NO_ANSWER, searchOutcome, 0, null, startNanos);
            return decorate(new RagAskResponse(NO_ANSWER, List.of(), debugChunks(searchResults, Set.of(), debugEnabled), new RagTokenUsage(0, 0, 0)), effectiveMode, searchOutcome);
        }

        int maxContextChars = effectiveMaxContextChars(questionLength);
        if (maxContextChars <= 0) {
            metricsService.recordRagNoAnswer();
            logAsk(requestId, questionLength, topK, scoreThreshold, searchResults, 0, NO_ANSWER, searchOutcome, 0, null, startNanos);
            return decorate(new RagAskResponse(NO_ANSWER, List.of(), debugChunks(searchResults, Set.of(), debugEnabled), new RagTokenUsage(0, 0, 0)), effectiveMode, searchOutcome);
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
            return decorate(new RagAskResponse(NO_ANSWER, List.of(), debugChunks(searchResults, Set.of(), debugEnabled), new RagTokenUsage(0, 0, 0)), effectiveMode, searchOutcome);
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
        if (conversational) {
            recordTurn(currentUser.getUserId(), conversationId, question, answer);
        }
        return decorate(new RagAskResponse(
                answer,
                contextBuildResult.sources(),
                debugChunks(searchResults, includedChunkIds, debugEnabled),
                new RagTokenUsage(
                        generationResult.getPromptTokens(),
                        generationResult.getCompletionTokens(),
                        generationResult.getTotalTokens()
                )
        ), effectiveMode, searchOutcome);
    }

    private static RagAskResponse decorate(RagAskResponse resp, RagProperties.RetrievalMode mode, RagSearchOutcome outcome) {
        resp.setEffectiveMode(mode);
        if (outcome != null) {
            resp.setEmbeddingDurationMs(outcome.embeddingDurationMs());
            resp.setSearchDurationMs(outcome.searchDurationMs());
        }
        return resp;
    }

    // 把本轮 (question, answer) 追加进会话历史；累计轮数超阈值时压缩早期轮次为摘要，供下一轮指代消解。
    private void recordTurn(String userId, String conversationId, String question, String answer) {
        conversationHistoryStore.append(userId, conversationId, new ConversationTurn(question, answer));

        RagProperties.Conversation conversation = ragProperties.getQueryRewrite().getConversation();
        int historyTurns = Math.max(1, conversation.getHistoryTurns());
        int summaryThreshold = Math.max(historyTurns, conversation.getSummaryThreshold());

        ConversationHistory history = conversationHistoryStore.get(userId, conversationId);
        List<ConversationTurn> turns = history.recentTurns();
        if (turns.size() <= summaryThreshold) {
            return;
        }

        int compressCount = turns.size() - historyTurns;
        List<ConversationTurn> toSummarize = turns.subList(0, compressCount);
        List<ConversationTurn> toKeep = List.copyOf(turns.subList(compressCount, turns.size()));
        String newSummary = queryRewriterService.summarizeHistory(history.summary(), toSummarize);
        // 摘要失败返回 null：放弃本次压缩，保留完整历史，避免丢上下文。
        if (newSummary != null) {
            conversationHistoryStore.replace(userId, conversationId, new ConversationHistory(newSummary, toKeep));
        }
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
        boolean parentEnabled = ragProperties.getChunk().getParentDocument().isEnabled();
        Set<String> usedParentIds = new java.util.HashSet<>();

        for (RagSearchResult result : searchResults.stream()
                .sorted(java.util.Comparator.comparingDouble(RagSearchResult::getScore).reversed())
                .toList()) {
            String parentId = result.getParentId();
            boolean useParent = parentEnabled && parentId != null;
            // 按 parentId 去重：一个父块只回填一次，避免同父多子块带来重复上下文。
            if (useParent && !usedParentIds.add(parentId)) {
                continue;
            }
            String contextContent = result.getContent();
            if (useParent) {
                contextContent = parentStore.findById(parentId)
                        .map(ParentBlock::getContent)
                        .orElse(result.getContent());
            }

            int index = sources.size() + 1;
            String section = """
                    [%d]
                    文件：%s
                    内容：%s

                    """.formatted(index, result.getFilename(), contextContent);
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
            // sources 仍指向命中的子块（chunkId/preview 用子块），回填的父块正文只进 LLM 上下文。
            RagSource source = new RagSource(
                    result.getDocumentId(),
                    result.getFilename(),
                    result.getChunkId(),
                    result.getScore(),
                    preview(result.getContent())
            );
            // 多模态：把命中图片的模态与引用透传给前端展示召回到的图片来源。
            source.setModality(result.getModality());
            source.setImageRef(result.getImageRef());
            sources.add(source);
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
