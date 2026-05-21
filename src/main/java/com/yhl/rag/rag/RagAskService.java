package com.yhl.rag.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmProperties;
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

    public RagAskService(
            RagSearchService ragSearchService,
            LlmClient llmClient,
            RagProperties ragProperties,
            LlmProperties llmProperties
    ) {
        this.ragSearchService = ragSearchService;
        this.llmClient = llmClient;
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
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

        List<RagSearchResult> searchResults = ragSearchService.search(question);
        if (searchResults.isEmpty()) {
            logAsk(requestId, questionLength, topK, scoreThreshold, searchResults, List.of(), 0, NO_ANSWER, startNanos);
            return new RagAskResponse(NO_ANSWER, List.of(), debugChunks(searchResults, Set.of(), debugEnabled));
        }

        int maxContextChars = effectiveMaxContextChars(questionLength);
        if (maxContextChars <= 0) {
            logAsk(requestId, questionLength, topK, scoreThreshold, searchResults, List.of(), 0, NO_ANSWER, startNanos);
            return new RagAskResponse(NO_ANSWER, List.of(), debugChunks(searchResults, Set.of(), debugEnabled));
        }

        ContextBuildResult contextBuildResult = buildContext(searchResults, maxContextChars);
        if (contextBuildResult.sources().isEmpty()) {
            logAsk(requestId, questionLength, topK, scoreThreshold, searchResults, List.of(), 0, NO_ANSWER, startNanos);
            return new RagAskResponse(NO_ANSWER, List.of(), debugChunks(searchResults, Set.of(), debugEnabled));
        }

        String prompt = """
                问题：
                %s

                资料：
                %s
                """.formatted(question, contextBuildResult.context());

        String answer = llmClient.generate(SYSTEM_PROMPT, List.of(new LlmMessage("user", prompt)));
        Set<String> includedChunkIds = contextBuildResult.sources().stream()
                .map(RagSource::getChunkId)
                .collect(Collectors.toSet());
        logAsk(
                requestId,
                questionLength,
                topK,
                scoreThreshold,
                searchResults,
                contextBuildResult.sources(),
                contextBuildResult.context().length(),
                answer,
                startNanos
        );
        return new RagAskResponse(answer, contextBuildResult.sources(), debugChunks(searchResults, includedChunkIds, debugEnabled));
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

        for (RagSearchResult result : searchResults) {
            int index = sources.size() + 1;
            String section = """
                    [%d]
                    文件：%s
                    内容：%s

                    """.formatted(index, result.getFilename(), result.getContent());

            if (context.length() + section.length() > maxChars) {
                break;
            }

            context.append(section);
            sources.add(new RagSource(
                    index,
                    result.getDocumentId(),
                    result.getFilename(),
                    result.getChunkId(),
                    result.getChunkIndex(),
                    result.getScore()
            ));
        }

        return new ContextBuildResult(context.toString(), sources);
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
            List<RagSource> sources,
            int contextChars,
            String answer,
            long startNanos
    ) {
        List<String> matchedChunkIds = searchResults.stream()
                .map(RagSearchResult::getChunkId)
                .toList();
        List<Double> scores = searchResults.stream()
                .map(RagSearchResult::getScore)
                .toList();

        log.info("rag_ask requestId={} questionLength={} topK={} scoreThreshold={} matchedChunkCount={} matchedChunkIds={} scores={} contextChars={} model={} durationMs={} answerLength={}",
                requestId,
                questionLength,
                topK,
                scoreThreshold,
                searchResults.size(),
                matchedChunkIds,
                scores,
                contextChars,
                llmProperties.getModel(),
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis(),
                answer == null ? 0 : answer.length());
    }

    private record ContextBuildResult(String context, List<RagSource> sources) {
    }
}
