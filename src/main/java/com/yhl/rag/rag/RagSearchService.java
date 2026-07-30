package com.yhl.rag.rag;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentService;
import com.yhl.rag.document.DocumentStatus;
import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.RerankClient;
import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.vector.VectorSearchRequest;
import com.yhl.rag.vector.VectorSearchResult;
import com.yhl.rag.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final MockCurrentUserProvider currentUserProvider;
    private final DocumentService documentService;
    private final RagSearchCache ragSearchCache;
    private final KnowledgeBaseVersionService knowledgeBaseVersionService;
    private final LlmProperties llmProperties;
    private final RerankClient rerankClient;

    @Autowired
    public RagSearchService(
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            RagProperties ragProperties,
            MockCurrentUserProvider currentUserProvider,
            DocumentService documentService,
            RagSearchCache ragSearchCache,
            KnowledgeBaseVersionService knowledgeBaseVersionService,
            LlmProperties llmProperties,
            RerankClient rerankClient
    ) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.currentUserProvider = currentUserProvider;
        this.documentService = documentService;
        this.ragSearchCache = ragSearchCache;
        this.knowledgeBaseVersionService = knowledgeBaseVersionService;
        this.llmProperties = llmProperties;
        this.rerankClient = rerankClient;
    }

    public RagSearchService(
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            RagProperties ragProperties,
            MockCurrentUserProvider currentUserProvider,
            DocumentService documentService,
            RagSearchCache ragSearchCache,
            KnowledgeBaseVersionService knowledgeBaseVersionService,
            LlmProperties llmProperties
    ) {
        this(
                embeddingClient,
                vectorStore,
                ragProperties,
                currentUserProvider,
                documentService,
                ragSearchCache,
                knowledgeBaseVersionService,
                llmProperties,
                null
        );
    }

    public RagSearchService(
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            RagProperties ragProperties,
            MockCurrentUserProvider currentUserProvider,
            DocumentService documentService
    ) {
        this(
                embeddingClient,
                vectorStore,
                ragProperties,
                currentUserProvider,
                documentService,
                new RagSearchCache(),
                new KnowledgeBaseVersionService(),
                new LlmProperties(),
                null
        );
    }

    public RagSearchService(
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            RagProperties ragProperties,
            MockCurrentUserProvider currentUserProvider
    ) {
        this(embeddingClient, vectorStore, ragProperties, currentUserProvider, null);
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
        return searchWithMetrics(question, currentUser, includeBelowThreshold, null, null);
    }

    public RagSearchOutcome searchWithMetrics(String question, CurrentUser currentUser, boolean includeBelowThreshold, Integer topKOverride) {
        return searchWithMetrics(question, currentUser, includeBelowThreshold, topKOverride, null);
    }

    /**
     * 检索主入口，支持请求级 mode 覆盖。
     *
     * @param modeOverride 请求级检索模式；为 null 时走全局配置 rag.search.mode（零回归）。
     *                     前端 Ask 页三模式并列对比会分别传 VECTOR / HYBRID / HYBRID_RERANK。
     */
    public RagSearchOutcome searchWithMetrics(String question, CurrentUser currentUser, boolean includeBelowThreshold, Integer topKOverride, RagProperties.RetrievalMode modeOverride) {
        long startNanos = System.nanoTime();
        int questionLength = question == null ? 0 : question.length();
        int configuredTopK = ragProperties.getSearch().getTopK();
        int topK = topKOverride == null ? configuredTopK : Math.min(topKOverride, configuredTopK);
        double scoreThreshold = ragProperties.getSearch().getScoreThreshold();
        long knowledgeBaseVersion = knowledgeBaseVersionService.currentVersion();
        String permissionSignature = ragSearchCache.permissionSignature(currentUser);
        String queryHash = ragSearchCache.queryHash(question);
        // 缓存 key 加入 mode 维度，避免不同 mode 的结果串味。
        RagProperties.RetrievalMode effectiveMode = modeOverride != null ? modeOverride : ragProperties.getSearch().getMode();
        String cacheKey = ragSearchCache.key(
                currentUser.getTenantId(),
                permissionSignature,
                question + "|mode=" + effectiveMode.name(),
                topK,
                scoreThreshold,
                llmProperties.getEmbeddingModel(),
                knowledgeBaseVersion
        );

        validateSearchConfig(topK, scoreThreshold);

        boolean vectorOnly = effectiveMode == RagProperties.RetrievalMode.VECTOR;

        if (!includeBelowThreshold && vectorOnly) {
            var cached = ragSearchCache.get(cacheKey);
            if (cached.isPresent()) {
                List<RagSearchResult> results = cached.get().getVectorResults().stream()
                        .filter(result -> documentService == null || documentService.canAccessChunk(result.getChunk(), currentUser))
                        .map(this::toRagSearchResult)
                        .toList();
                logSearch(
                        "hit",
                        questionLength,
                        topK,
                        scoreThreshold,
                        includeBelowThreshold,
                        results,
                        cached.get().getEmbeddingDurationMs(),
                        cached.get().getSearchDurationMs(),
                        startNanos,
                        currentUser.getTenantId(),
                        permissionSignature,
                        queryHash,
                        knowledgeBaseVersion
                );
                return new RagSearchOutcome(results, cached.get().getEmbeddingDurationMs(), cached.get().getSearchDurationMs());
            }
            log.info("rag_search_cache_miss tenantId={} permissionSignature={} queryHash={} topK={} scoreThreshold={} embeddingModel={} knowledgeBaseVersion={}",
                    currentUser.getTenantId(),
                    permissionSignature,
                    queryHash,
                    topK,
                    scoreThreshold,
                    llmProperties.getEmbeddingModel(),
                    knowledgeBaseVersion);
        }

        long embeddingStartNanos = System.nanoTime();
        List<Double> questionVector = embeddingClient.embed(question);
        long embeddingDurationMs = elapsedMillis(embeddingStartNanos);
        validateVector(questionVector, "问题向量为空");

        RagProperties.RetrievalMode mode = effectiveMode;
        boolean hybrid = mode != RagProperties.RetrievalMode.VECTOR;
        // 召回阶段：hybrid 模式下两路各召回 recallTopK 候选，融合后再裁剪到 topK。
        int recallTopK = hybrid ? Math.max(ragProperties.getSearch().getRecallTopK(), topK) : topK;

        long searchStartNanos = System.nanoTime();
        VectorSearchRequest vectorRequest = new VectorSearchRequest();
        vectorRequest.setQueryVector(questionVector);
        vectorRequest.setTopK(recallTopK);
        vectorRequest.setScoreThreshold(scoreThreshold);
        // hybrid 召回放宽阈值，让 BM25 命中的精确关键词文档也能进融合池。
        vectorRequest.setIncludeBelowThreshold(includeBelowThreshold || hybrid);
        vectorRequest.setTenantId(currentUser.getTenantId());
        vectorRequest.setUserId(currentUser.getUserId());
        vectorRequest.setDepartment(currentUser.getDepartment());
        vectorRequest.setDepartmentIds(currentUser.getDepartmentIds());
        vectorRequest.setRoleIds(currentUser.getRoleIds());
        vectorRequest.setStatus(DocumentStatus.ACTIVE);
        vectorRequest.setDocumentStatus(DocumentStatus.READY);
        if (documentService != null) {
            Map<String, Integer> readyDocumentVersions = documentService.getReadyDocumentVersionSnapshot();
            vectorRequest.setDocumentVersions(readyDocumentVersions);
        }

        List<VectorSearchResult> vectorResults;
        if (hybrid) {
            boolean rerank = mode == RagProperties.RetrievalMode.HYBRID_RERANK
                    && rerankClient != null && rerankClient.isConfigured();
            // 需要精排时，先把融合池放大到 recallTopK，交给 Cross-Encoder 精选；否则直接裁到 topK。
            int fusionLimit = rerank ? recallTopK : topK;
            List<VectorSearchResult> denseResults = vectorStore.search(vectorRequest);
            List<VectorSearchResult> keywordResults = vectorStore.keywordSearch(question, vectorRequest);
            List<VectorSearchResult> fused = fuseWithRrf(denseResults, keywordResults, ragProperties.getSearch().getRrfK(), fusionLimit);
            vectorResults = rerank ? rerankResults(question, fused, topK) : fused;
            log.info("rag_hybrid_recall mode={} denseCount={} keywordCount={} fusedCount={} rerank={} finalCount={} rrfK={}",
                    mode, denseResults.size(), keywordResults.size(), fused.size(), rerank, vectorResults.size(), ragProperties.getSearch().getRrfK());
        } else {
            vectorResults = vectorStore.search(vectorRequest);
        }
        if (!includeBelowThreshold && !hybrid) {
            ragSearchCache.put(cacheKey, vectorResults, embeddingDurationMs, elapsedMillis(searchStartNanos));
        }
        List<RagSearchResult> results = vectorResults.stream()
                .filter(result -> documentService == null || documentService.canAccessChunk(result.getChunk(), currentUser))
                .map(this::toRagSearchResult)
                .toList();
        logSearch(
                "miss",
                questionLength,
                topK,
                scoreThreshold,
                includeBelowThreshold,
                results,
                embeddingDurationMs,
                elapsedMillis(searchStartNanos),
                startNanos,
                currentUser.getTenantId(),
                permissionSignature,
                queryHash,
                knowledgeBaseVersion
        );
        return new RagSearchOutcome(results, embeddingDurationMs, elapsedMillis(searchStartNanos));
    }

    private void logSearch(
            String cacheStatus,
            int questionLength,
            int topK,
            double scoreThreshold,
            boolean includeBelowThreshold,
            List<RagSearchResult> results,
            long embeddingDurationMs,
            long searchDurationMs,
            long startNanos,
            String tenantId,
            String permissionSignature,
            String queryHash,
            long knowledgeBaseVersion
    ) {
        double maxScore = results.isEmpty() ? 0.0 : results.get(0).getScore();
        double minScore = results.isEmpty() ? 0.0 : results.get(results.size() - 1).getScore();
        long matchedCount = results.stream()
                .filter(RagSearchResult::isIncluded)
                .count();

        log.info("rag_search cache={} tenantId={} permissionSignature={} queryHash={} knowledgeBaseVersion={} questionLength={} topK={} scoreThreshold={} includeBelowThreshold={} retrievedCount={} usedChunkCount={} minScore={} maxScore={} noAnswerFallback={} embeddingDurationMs={} searchDurationMs={} durationMs={}",
                cacheStatus,
                tenantId,
                permissionSignature,
                queryHash,
                knowledgeBaseVersion,
                questionLength,
                topK,
                scoreThreshold,
                includeBelowThreshold,
                results.size(),
                matchedCount,
                minScore,
                maxScore,
                false,
                embeddingDurationMs,
                searchDurationMs,
                elapsedMillis(startNanos));
    }

    /**
     * RRF（Reciprocal Rank Fusion，倒数排名融合）：不依赖两路分数量纲，只看各自排名。
     * 文档在某路排第 r 名（从 1 起）得 1/(k + r) 分，多路相加后重排，取前 limit。
     */
    private List<VectorSearchResult> fuseWithRrf(
            List<VectorSearchResult> denseResults,
            List<VectorSearchResult> keywordResults,
            int rrfK,
            int limit
    ) {
        Map<String, Double> fusedScore = new java.util.LinkedHashMap<>();
        Map<String, VectorSearchResult> chunkById = new java.util.LinkedHashMap<>();
        accumulateRrf(denseResults, rrfK, fusedScore, chunkById);
        accumulateRrf(keywordResults, rrfK, fusedScore, chunkById);

        return fusedScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    VectorSearchResult base = chunkById.get(entry.getKey());
                    return new VectorSearchResult(base.getChunk(), entry.getValue(), true,
                            "rrf score=" + String.format(java.util.Locale.ROOT, "%.4f", entry.getValue()));
                })
                .toList();
    }

    /**
     * Cross-Encoder 精排：把融合候选的正文送 bge-reranker，按相关性重排后取前 topK。
     * rerank 调用失败时降级返回原融合顺序的前 topK，保证检索链路不因外部依赖中断。
     */
    private List<VectorSearchResult> rerankResults(String question, List<VectorSearchResult> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        try {
            List<String> documents = candidates.stream()
                    .map(result -> result.getChunk().getContent())
                    .toList();
            List<Integer> orderedIndexes = rerankClient.rerank(question, documents, topK);
            if (orderedIndexes.isEmpty()) {
                return candidates.stream().limit(topK).toList();
            }
            return orderedIndexes.stream()
                    .limit(topK)
                    .map(candidates::get)
                    .map(result -> new VectorSearchResult(result.getChunk(), result.getScore(), true, "reranked"))
                    .toList();
        } catch (LlmException exception) {
            log.warn("rag_rerank_fallback errorType={} message={}", exception.getErrorType(), exception.getMessage());
            return candidates.stream().limit(topK).toList();
        }
    }

    private void accumulateRrf(
            List<VectorSearchResult> results,
            int rrfK,
            Map<String, Double> fusedScore,
            Map<String, VectorSearchResult> chunkById
    ) {
        for (int rank = 0; rank < results.size(); rank++) {
            VectorSearchResult result = results.get(rank);
            String chunkId = result.getChunk().getChunkId();
            double contribution = 1.0 / (rrfK + rank + 1);
            fusedScore.merge(chunkId, contribution, Double::sum);
            chunkById.putIfAbsent(chunkId, result);
        }
    }

    private RagSearchResult toRagSearchResult(VectorSearchResult vectorResult) {
        DocumentChunk chunk = vectorResult.getChunk();
        RagSearchResult result = new RagSearchResult(
                chunk.getChunkId(),
                chunk.getDocumentId(),
                chunk.getFilename(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                vectorResult.getScore(),
                vectorResult.getDebugInfo(),
                vectorResult.isIncluded()
        );
        result.setParentId(chunk.getParentId());
        result.setModality(chunk.getModality() == null ? null : chunk.getModality().name());
        result.setImageRef(chunk.getImageRef());
        return result;
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
