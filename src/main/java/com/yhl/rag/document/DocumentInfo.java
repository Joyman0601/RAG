package com.yhl.rag.document;

import java.time.Instant;

public class DocumentInfo {

    private String id;

    private String filename;

    private String contentType;

    private long size;

    private Instant createdAt;

    public DocumentInfo() {
    }

    public DocumentInfo(String id, String filename, String contentType, long size, Instant createdAt) {
        this.id = id;
        this.filename = filename;
        this.contentType = contentType;
        this.size = size;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
