package com.yhl.rag.vector;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public List<VectorSearchResult> keywordSearch(String queryText, VectorSearchRequest request) {
        if (request == null || !StringUtils.hasText(queryText)) {
            return List.of();
        }

        List<DocumentChunk> corpus = entries.values().stream()
                .map(Entry::chunk)
                .filter(chunk -> matchesFilter(chunk, request))
                .toList();
        if (corpus.isEmpty()) {
            return List.of();
        }

        List<List<String>> docTokens = corpus.stream()
                .map(chunk -> tokenize(chunk.getContent()))
                .toList();
        List<String> queryTokens = tokenize(queryText).stream().distinct().toList();
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        int docCount = corpus.size();
        double avgDocLength = docTokens.stream().mapToInt(List::size).average().orElse(0.0);

        Map<String, Integer> documentFrequency = new java.util.HashMap<>();
        for (List<String> tokens : docTokens) {
            for (String term : Set.copyOf(tokens)) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        double k1 = 1.5;
        double b = 0.75;
        int topK = Math.max(request.getTopK(), 1);

        return java.util.stream.IntStream.range(0, corpus.size())
                .mapToObj(i -> {
                    double score = bm25Score(queryTokens, docTokens.get(i), documentFrequency, docCount, avgDocLength, k1, b);
                    return new VectorSearchResult(copyChunk(corpus.get(i)), score, score > 0, "bm25");
                })
                .filter(result -> result.getScore() > 0)
                .sorted(Comparator.comparingDouble(VectorSearchResult::getScore).reversed())
                .limit(topK)
                .toList();
    }

    private static double bm25Score(
            List<String> queryTokens,
            List<String> docTokens,
            Map<String, Integer> documentFrequency,
            int docCount,
            double avgDocLength,
            double k1,
            double b
    ) {
        Map<String, Integer> termFrequency = new java.util.HashMap<>();
        for (String term : docTokens) {
            termFrequency.merge(term, 1, Integer::sum);
        }
        int docLength = docTokens.size();
        double score = 0.0;
        for (String term : queryTokens) {
            int tf = termFrequency.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
            double denominator = tf + k1 * (1 - b + b * (avgDocLength == 0 ? 0 : docLength / avgDocLength));
            score += idf * (tf * (k1 + 1)) / denominator;
        }
        return score;
    }

    /** 轻量分词：CJK 字符切相邻二元组（bigram），ASCII 连续字母/数字作为词元；学习项目够用，无需第三方分词库。 */
    private static List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String lower = text.toLowerCase();
        List<String> tokens = new java.util.ArrayList<>();
        StringBuilder asciiToken = new StringBuilder();
        List<Character> cjkRun = new java.util.ArrayList<>();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (!cjkRun.isEmpty()) {
                    addCjkBigrams(cjkRun, tokens);
                    cjkRun.clear();
                }
                asciiToken.append(c);
            } else if (isCjk(c)) {
                if (asciiToken.length() > 0) {
                    tokens.add(asciiToken.toString());
                    asciiToken.setLength(0);
                }
                cjkRun.add(c);
            } else {
                if (asciiToken.length() > 0) {
                    tokens.add(asciiToken.toString());
                    asciiToken.setLength(0);
                }
                if (!cjkRun.isEmpty()) {
                    addCjkBigrams(cjkRun, tokens);
                    cjkRun.clear();
                }
            }
        }
        if (asciiToken.length() > 0) {
            tokens.add(asciiToken.toString());
        }
        if (!cjkRun.isEmpty()) {
            addCjkBigrams(cjkRun, tokens);
        }
        return tokens;
    }

    private static void addCjkBigrams(List<Character> cjkRun, List<String> tokens) {
        if (cjkRun.size() == 1) {
            tokens.add(String.valueOf(cjkRun.get(0)));
            return;
        }
        for (int i = 0; i < cjkRun.size() - 1; i++) {
            tokens.add("" + cjkRun.get(i) + cjkRun.get(i + 1));
        }
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
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
        if (!StringUtils.hasText(request.getTenantId()) || !request.getTenantId().equals(chunk.getTenantId())) {
            return false;
        }
        if (request.getStatus() != null && chunk.getStatus() != request.getStatus()) {
            return false;
        }
        if (request.getDocumentStatus() != null && chunk.getDocumentStatus() != request.getDocumentStatus()) {
            return false;
        }
        if (request.getVersion() != null && chunk.getVersion() != request.getVersion()) {
            return false;
        }
        if (request.getDocumentVersions() != null) {
            Integer currentVersion = request.getDocumentVersions().get(chunk.getDocumentId());
            if (currentVersion == null || chunk.getVersion() != currentVersion) {
                return false;
            }
        }
        if (request.getVisibility() != null && chunk.getVisibility() != request.getVisibility()) {
            return false;
        }
        return canAccess(chunk, request);
    }

    private boolean canAccess(DocumentChunk chunk, VectorSearchRequest request) {
        DocumentVisibility visibility = chunk.getVisibility();
        if (visibility == DocumentVisibility.PRIVATE) {
            return StringUtils.hasText(request.getUserId()) && request.getUserId().equals(chunk.getOwnerId());
        }
        if (visibility == DocumentVisibility.DEPARTMENT) {
            return request.getDepartmentIds().contains(chunk.getDepartmentId());
        }
        if (visibility == DocumentVisibility.TENANT || visibility == DocumentVisibility.PUBLIC) {
            return true;
        }
        if (visibility == DocumentVisibility.CUSTOM) {
            return containsAny(chunk.getAllowedUserIds(), singletonIfText(request.getUserId()))
                    || containsAny(chunk.getAllowedRoleIds(), request.getRoleIds());
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
        DocumentChunk copied = new DocumentChunk(
                chunk.getChunkId(),
                chunk.getDocumentId(),
                chunk.getFilename(),
                chunk.getContent(),
                chunk.getContentHash(),
                chunk.getChunkIndex(),
                chunk.getCreatedAt(),
                chunk.getTenantId(),
                chunk.getOwnerId(),
                chunk.getDepartmentId(),
                chunk.getVisibility(),
                chunk.getAllowedUserIds(),
                chunk.getAllowedRoleIds(),
                chunk.getStatus() == null ? DocumentStatus.ACTIVE : chunk.getStatus(),
                chunk.getVersion(),
                chunk.getPermissionLevel()
        );
        copied.setDocumentStatus(chunk.getDocumentStatus() == null ? DocumentStatus.READY : chunk.getDocumentStatus());
        return copied;
    }

    private static boolean containsAny(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        for (String value : right) {
            if (left.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> singletonIfText(String value) {
        return StringUtils.hasText(value) ? Set.of(value) : Set.of();
    }

    private record Entry(DocumentChunk chunk, List<Double> embedding) {
    }
}
