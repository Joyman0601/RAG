package com.yhl.rag.document;

import java.time.Instant;

public class DocumentChunk {

    private String chunkId;

    private String documentId;

    private String filename;

    private String content;

    private int chunkIndex;

    private Instant createdAt;

    public DocumentChunk() {
    }

    public DocumentChunk(String chunkId, String documentId, String filename, String content, int chunkIndex, Instant createdAt) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.filename = filename;
        this.content = content;
        this.chunkIndex = chunkIndex;
        this.createdAt = createdAt;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
