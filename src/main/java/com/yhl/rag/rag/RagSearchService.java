package com.yhl.rag.rag;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentService;
import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    private final EmbeddingClient embeddingClient;
    private final DocumentService documentService;
    private final RagProperties ragProperties;

    public RagSearchService(EmbeddingClient embeddingClient, DocumentService documentService, RagProperties ragProperties) {
        this.embeddingClient = embeddingClient;
        this.documentService = documentService;
        this.ragProperties = ragProperties;
    }

    public List<RagSearchResult> search(String question) {
        return search(question, false);
    }

    public List<RagSearchResult> search(String question, boolean includeBelowThreshold) {
        long startNanos = System.nanoTime();
        int questionLength = question == null ? 0 : question.length();
        int topK = ragProperties.getSearch().getTopK();
        double scoreThreshold = ragProperties.getSearch().getScoreThreshold();

        validateSearchConfig(topK, scoreThreshold);

        List<Double> questionVector = embeddingClient.embed(question);
        validateVector(questionVector, "问题向量为空");

        List<DocumentChunk> chunks = documentService.listAllChunks();
        Map<String, List<Double>> embeddings = documentService.getChunkEmbeddingsSnapshot();

        List<RagSearchResult> candidates = chunks.stream()
                .filter(chunk -> embeddings.containsKey(chunk.getChunkId()))
                .map(chunk -> toSearchResult(chunk, questionVector, embeddings.get(chunk.getChunkId())))
                .sorted(Comparator.comparingDouble(RagSearchResult::getScore).reversed())
                .toList();

        List<RagSearchResult> topCandidates = candidates.stream()
                .limit(topK)
                .map(result -> markIncluded(result, scoreThreshold))
                .toList();
        double maxScore = topCandidates.isEmpty() ? 0.0 : topCandidates.get(0).getScore();
        double minScore = topCandidates.isEmpty() ? 0.0 : topCandidates.get(topCandidates.size() - 1).getScore();

        List<RagSearchResult> results = topCandidates.stream()
                .filter(result -> includeBelowThreshold || result.isIncluded())
                .toList();

        log.info("rag_search questionLength={} topK={} scoreThreshold={} includeBelowThreshold={} candidateCount={} returnedCount={} minScore={} maxScore={} durationMs={}",
                questionLength,
                topK,
                scoreThreshold,
                includeBelowThreshold,
                candidates.size(),
                results.size(),
                minScore,
                maxScore,
                elapsedMillis(startNanos));
        return results;
    }

    private static RagSearchResult toSearchResult(DocumentChunk chunk, List<Double> questionVector, List<Double> chunkVector) {
        validateVector(chunkVector, "chunk 向量为空：" + chunk.getChunkId());
        double score = cosineSimilarity(questionVector, chunkVector, chunk.getChunkId());
        return new RagSearchResult(
                chunk.getChunkId(),
                chunk.getDocumentId(),
                chunk.getFilename(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                score
        );
    }

    private static RagSearchResult markIncluded(RagSearchResult result, double scoreThreshold) {
        boolean included = result.getScore() >= scoreThreshold;
        result.setIncluded(included);
        result.setDebugInfo(included ? "score >= threshold" : "score < threshold");
        return result;
    }

    private static double cosineSimilarity(List<Double> left, List<Double> right, String chunkId) {
        if (left.size() != right.size()) {
            throw new LlmException(
                    LlmErrorType.INVALID_STRUCTURED_OUTPUT,
                    "向量维度不一致，chunkId=" + chunkId + "，questionDimension=" + left.size()
                            + "，chunkDimension=" + right.size()
            );
        }

        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double leftValue = left.get(i);
            double rightValue = right.get(i);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }

        if (leftNorm == 0 || rightNorm == 0) {
            throw new LlmException(LlmErrorType.EMPTY_EMBEDDING, "向量范数为 0，无法计算相似度");
        }

        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
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
