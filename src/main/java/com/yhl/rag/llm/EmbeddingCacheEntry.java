package com.yhl.rag.llm;

import java.time.Instant;
import java.util.List;

public class EmbeddingCacheEntry {

    private final List<Double> vector;

    private final int tokenCount;

    private final Instant createdAt;

    public EmbeddingCacheEntry(List<Double> vector, int tokenCount, Instant createdAt) {
        this.vector = vector == null ? List.of() : List.copyOf(vector);
        this.tokenCount = tokenCount;
        this.createdAt = createdAt;
    }

    public List<Double> getVector() {
        return vector;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
