package com.yhl.rag.vector;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentStatus;
import com.yhl.rag.document.DocumentVisibility;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InMemoryVectorStore implements VectorStore {

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void save(DocumentChunk chunk, List<Double> embedding) {
        validateVector(embedding, "chunk 向量为空：" + (chunk == null ? null : chunk.getChunkId()));
        if (chunk == null || !StringUtils.hasText(chunk.getChunkId())) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "chunkId 不能为空");
        }
        entries.put(chunk.getChunkId(), new Entry(copyChunk(chunk), List.copyOf(embedding)));
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        validateSearchRequest(request);
        List<Double> queryVector = request.getQueryVector();

        List<VectorSearchResult> topCandidates = entries.values().stream()
                .filter(entry -> matchesFilter(entry.chunk(), request))
                .map(entry -> toSearchResult(entry, queryVector, request.getScoreThreshold()))
                .sorted(Comparator.comparingDouble(VectorSearchResult::getScore).reversed())
                .limit(request.getTopK())
                .toList();

        if (request.isIncludeBelowThreshold()) {
            return topCandidates;
        }
        return topCandidates.stream()
                .filter(VectorSearchResult::isIncluded)
                .toList();
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        entries.entrySet().removeIf(entry -> documentId.equals(entry.getValue().chunk().getDocumentId()));
    }

    @Override
    public void deleteByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        for (String chunkId : chunkIds) {
            if (StringUtils.hasText(chunkId)) {
                entries.remove(chunkId);
            }
        }
    }

    @Override
    public List<Double> getEmbedding(String chunkId) {
        Entry entry = entries.get(chunkId);
        return entry == null ? null : entry.embedding();
    }

    @Override
    public Map<String, List<Double>> getEmbeddingSnapshot() {
        return entries.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().embedding()
                ));
    }

    private VectorSearchResult toSearchResult(Entry entry, List<Double> queryVector, double scoreThreshold) {
        double score = cosineSimilarity(queryVector, entry.embedding(), entry.chunk().getChunkId());
        boolean included = score >= scoreThreshold;
        return new VectorSearchResult(
                copyChunk(entry.chunk()),
                score,
                included,
                included ? "score >= threshold" : "score < threshold"
        );
    }

    private boolean matchesFilter(DocumentChunk chunk, VectorSearchRequest request) {
        if (request.getStatus() != null && chunk.getStatus() != request.getStatus()) {
            return false;
        }
        if (request.getVersion() != null && chunk.getVersion() != request.getVersion()) {
            return false;
        }
        if (request.getVisibility() != null && chunk.getVisibility() != request.getVisibility()) {
            return false;
        }
        return canAccess(chunk, request.getUserId(), request.getDepartment());
    }

    private boolean canAccess(DocumentChunk chunk, String userId, String department) {
        DocumentVisibility visibility = chunk.getVisibility();
        if (visibility == DocumentVisibility.PUBLIC) {
            return true;
        }
        if (visibility == DocumentVisibility.PRIVATE) {
            return StringUtils.hasText(userId) && userId.equals(chunk.getOwnerId());
        }
        if (visibility == DocumentVisibility.INTERNAL) {
            return StringUtils.hasText(department) && department.equals(chunk.getDepartment());
        }
        return false;
    }

    private void validateSearchRequest(VectorSearchRequest request) {
        if (request == null) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "vector search request 不能为空");
        }
        validateVector(request.getQueryVector(), "问题向量为空");
        if (request.getTopK() <= 0) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "topK 必须大于 0");
        }
        if (request.getScoreThreshold() < -1 || request.getScoreThreshold() > 1) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "scoreThreshold 必须在 -1 到 1 之间");
        }
    }

    private static void validateVector(List<Double> vector, String message) {
        if (vector == null || vector.isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_EMBEDDING, message);
        }
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

    private static DocumentChunk copyChunk(DocumentChunk chunk) {
        return new DocumentChunk(
                chunk.getChunkId(),
                chunk.getDocumentId(),
                chunk.getFilename(),
                chunk.getContent(),
                chunk.getContentHash(),
                chunk.getChunkIndex(),
                chunk.getCreatedAt(),
                chunk.getStatus() == null ? DocumentStatus.ACTIVE : chunk.getStatus(),
                chunk.getVersion(),
                chunk.getOwnerId(),
                chunk.getDepartment(),
                chunk.getVisibility(),
                chunk.getPermissionLevel()
        );
    }

    private record Entry(DocumentChunk chunk, List<Double> embedding) {
    }
}
