package com.yhl.rag.rag;

import java.time.Instant;
import java.util.List;

import com.yhl.rag.vector.VectorSearchResult;

public class RagSearchCacheEntry {

    private final List<VectorSearchResult> vectorResults;

    private final long embeddingDurationMs;

    private final long searchDurationMs;

    private final Instant createdAt;

    public RagSearchCacheEntry(
            List<VectorSearchResult> vectorResults,
            long embeddingDurationMs,
            long searchDurationMs,
            Instant createdAt
    ) {
        this.vectorResults = vectorResults == null ? List.of() : List.copyOf(vectorResults);
        this.embeddingDurationMs = embeddingDurationMs;
        this.searchDurationMs = searchDurationMs;
        this.createdAt = createdAt;
    }

    public List<VectorSearchResult> getVectorResults() {
        return vectorResults;
    }

    public long getEmbeddingDurationMs() {
        return embeddingDurationMs;
    }

    public long getSearchDurationMs() {
        return searchDurationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
