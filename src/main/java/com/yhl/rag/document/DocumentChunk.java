package com.yhl.rag.document;

import java.time.Instant;

public class DocumentChunk {

    private String chunkId;

    private String documentId;

    private String filename;

    private String content;

    private String contentHash;

    private int chunkIndex;

    private Instant createdAt;

    private DocumentStatus status = DocumentStatus.ACTIVE;

    private int version = 1;

    private String ownerId;

    private String department;

    private DocumentVisibility visibility;

    private int permissionLevel;

    public DocumentChunk() {
    }

    public DocumentChunk(String chunkId, String documentId, String filename, String content, int chunkIndex, Instant createdAt) {
        this(chunkId, documentId, filename, content, null, chunkIndex, createdAt, DocumentStatus.ACTIVE, 1, null, null, DocumentVisibility.INTERNAL, 0);
    }

    public DocumentChunk(
            String chunkId,
            String documentId,
            String filename,
            String content,
            String contentHash,
            int chunkIndex,
            Instant createdAt,
            DocumentStatus status,
            int version,
            String ownerId,
            String department,
            DocumentVisibility visibility,
            int permissionLevel
    ) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.filename = filename;
        this.content = content;
        this.contentHash = contentHash;
        this.chunkIndex = chunkIndex;
        this.createdAt = createdAt;
        this.status = status;
        this.version = version;
        this.ownerId = ownerId;
        this.department = department;
        this.visibility = visibility;
        this.permissionLevel = permissionLevel;
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

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
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

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public DocumentVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(DocumentVisibility visibility) {
        this.visibility = visibility;
    }

    public int getPermissionLevel() {
        return permissionLevel;
    }

    public void setPermissionLevel(int permissionLevel) {
        this.permissionLevel = permissionLevel;
    }
}
