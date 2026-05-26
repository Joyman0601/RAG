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

    @Autowired
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
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.currentUserProvider = currentUserProvider;
        this.documentService = documentService;
        this.ragSearchCache = ragSearchCache;
        this.knowledgeBaseVersionService = knowledgeBaseVersionService;
        this.llmProperties = llmProperties;
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
                new LlmProperties()
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
        return searchWithMetrics(question, currentUser, includeBelowThreshold, null);
    }

    public RagSearchOutcome searchWithMetrics(String question, CurrentUser currentUser, boolean includeBelowThreshold, Integer topKOverride) {
        long startNanos = System.nanoTime();
        int questionLength = question == null ? 0 : question.length();
        int configuredTopK = ragProperties.getSearch().getTopK();
        int topK = topKOverride == null ? configuredTopK : Math.min(topKOverride, configuredTopK);
        double scoreThreshold = ragProperties.getSearch().getScoreThreshold();
        long knowledgeBaseVersion = knowledgeBaseVersionService.currentVersion();
        String permissionSignature = ragSearchCache.permissionSignature(currentUser);
        String queryHash = ragSearchCache.queryHash(question);
        String cacheKey = ragSearchCache.key(
                currentUser.getTenantId(),
                permissionSignature,
                question,
                topK,
                scoreThreshold,
                llmProperties.getEmbeddingModel(),
                knowledgeBaseVersion
        );

        validateSearchConfig(topK, scoreThreshold);

        if (!includeBelowThreshold) {
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

        long searchStartNanos = System.nanoTime();
        VectorSearchRequest vectorRequest = new VectorSearchRequest();
        vectorRequest.setQueryVector(questionVector);
        vectorRequest.setTopK(topK);
        vectorRequest.setScoreThreshold(scoreThreshold);
        vectorRequest.setIncludeBelowThreshold(includeBelowThreshold);
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

        List<VectorSearchResult> vectorResults = vectorStore.search(vectorRequest);
        if (!includeBelowThreshold) {
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
