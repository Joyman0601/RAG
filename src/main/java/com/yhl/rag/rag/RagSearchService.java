package com.yhl.rag.rag;

import java.time.Duration;
import java.util.List;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentStatus;
import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.vector.VectorSearchRequest;
import com.yhl.rag.vector.VectorSearchResult;
import com.yhl.rag.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final MockCurrentUserProvider currentUserProvider;

    public RagSearchService(
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            RagProperties ragProperties,
            MockCurrentUserProvider currentUserProvider
    ) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.currentUserProvider = currentUserProvider;
    }

    public List<RagSearchResult> search(String question) {
        return search(question, false);
    }

    public List<RagSearchResult> search(String question, boolean includeBelowThreshold) {
        return searchWithMetrics(question, includeBelowThreshold).results();
    }

    public RagSearchOutcome searchWithMetrics(String question) {
        return searchWithMetrics(question, false);
    }

    public RagSearchOutcome searchWithMetrics(String question, boolean includeBelowThreshold) {
        return searchWithMetrics(question, currentUserProvider.getCurrentUser(), includeBelowThreshold);
    }

    public RagSearchOutcome searchWithMetrics(String question, CurrentUser currentUser, boolean includeBelowThreshold) {
        long startNanos = System.nanoTime();
        int questionLength = question == null ? 0 : question.length();
        int topK = ragProperties.getSearch().getTopK();
        double scoreThreshold = ragProperties.getSearch().getScoreThreshold();

        validateSearchConfig(topK, scoreThreshold);

        long embeddingStartNanos = System.nanoTime();
        List<Double> questionVector = embeddingClient.embed(question);
        long embeddingDurationMs = elapsedMillis(embeddingStartNanos);
        validateVector(questionVector, "问题向量为空");

        long searchStartNanos = System.nanoTime();
        VectorSearchRequest vectorRequest = new VectorSearchRequest();
        vectorRequest.setQueryVector(questionVector);
        vectorRequest.setTopK(topK);
        vectorRequest.setScoreThreshold(scoreThreshold);
        vectorRequest.setIncludeBelowThreshold(includeBelowThreshold);
        vectorRequest.setUserId(currentUser.getUserId());
        vectorRequest.setDepartment(currentUser.getDepartment());
        vectorRequest.setStatus(DocumentStatus.ACTIVE);

        List<VectorSearchResult> vectorResults = vectorStore.search(vectorRequest);
        List<RagSearchResult> results = vectorResults.stream()
                .map(this::toRagSearchResult)
                .toList();
        double maxScore = results.isEmpty() ? 0.0 : results.get(0).getScore();
        double minScore = results.isEmpty() ? 0.0 : results.get(results.size() - 1).getScore();

        long matchedCount = results.stream()
                .filter(RagSearchResult::isIncluded)
                .count();

        log.info("rag_search questionLength={} topK={} scoreThreshold={} includeBelowThreshold={} matchedCount={} returnedCount={} minScore={} maxScore={} durationMs={}",
                questionLength,
                topK,
                scoreThreshold,
                includeBelowThreshold,
                matchedCount,
                results.size(),
                minScore,
                maxScore,
                elapsedMillis(startNanos));
        return new RagSearchOutcome(results, embeddingDurationMs, elapsedMillis(searchStartNanos));
    }

    private RagSearchResult toRagSearchResult(VectorSearchResult vectorResult) {
        DocumentChunk chunk = vectorResult.getChunk();
        return new RagSearchResult(
                chunk.getChunkId(),
                chunk.getDocumentId(),
                chunk.getFilename(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                vectorResult.getScore(),
                vectorResult.getDebugInfo(),
                vectorResult.isIncluded()
        );
    }

    private static void validateVector(List<Double> vector, String message) {
        if (vector == null || vector.isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_EMBEDDING, message);
        }
    }

    private static void validateSearchConfig(int topK, double scoreThreshold) {
        if (topK <= 0) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "rag.search.top-k 必须大于 0");
        }
        if (scoreThreshold < -1 || scoreThreshold > 1) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "rag.search.score-threshold 必须在 -1 到 1 之间");
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
