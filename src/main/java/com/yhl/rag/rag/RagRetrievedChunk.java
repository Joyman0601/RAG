package com.yhl.rag.rag;

public class RagRetrievedChunk {

    private String chunkId;

    private String documentId;

    private String filename;

    private int chunkIndex;

    private double score;

    private boolean includedInContext;

    private String contentPreview;

    public RagRetrievedChunk() {
    }

    public RagRetrievedChunk(
            String chunkId,
            String documentId,
            String filename,
            int chunkIndex,
            double score,
            boolean includedInContext,
            String contentPreview
    ) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.filename = filename;
        this.chunkIndex = chunkIndex;
        this.score = score;
        this.includedInContext = includedInContext;
        this.contentPreview = contentPreview;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public boolean isIncludedInContext() {
        return includedInContext;
    }

    public void setIncludedInContext(boolean includedInContext) {
        this.includedInContext = includedInContext;
    }

    public String getContentPreview() {
        return contentPreview;
    }

    public void setContentPreview(String contentPreview) {
        this.contentPreview = contentPreview;
    }
}
