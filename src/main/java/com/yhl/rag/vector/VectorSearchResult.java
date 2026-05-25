package com.yhl.rag.vector;

import com.yhl.rag.document.DocumentChunk;

public class VectorSearchResult {

    private DocumentChunk chunk;

    private double score;

    private boolean included;

    private String debugInfo;

    public VectorSearchResult() {
    }

    public VectorSearchResult(DocumentChunk chunk, double score, boolean included, String debugInfo) {
        this.chunk = chunk;
        this.score = score;
        this.included = included;
        this.debugInfo = debugInfo;
    }

    public DocumentChunk getChunk() {
        return chunk;
    }

    public void setChunk(DocumentChunk chunk) {
        this.chunk = chunk;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public boolean isIncluded() {
        return included;
    }

    public void setIncluded(boolean included) {
        this.included = included;
    }

    public String getDebugInfo() {
        return debugInfo;
    }

    public void setDebugInfo(String debugInfo) {
        this.debugInfo = debugInfo;
    }
}
