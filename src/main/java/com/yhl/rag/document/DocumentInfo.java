package com.yhl.rag.document;

import java.time.Instant;

public class DocumentInfo {

    private String id;

    private String filename;

    private String contentType;

    private long size;

    private Instant createdAt;

    private DocumentStatus status = DocumentStatus.ACTIVE;

    private int version = 1;

    private String ownerId;

    private String department;

    private DocumentVisibility visibility;

    private int permissionLevel;

    public DocumentInfo() {
    }

    public DocumentInfo(String id, String filename, String contentType, long size, Instant createdAt) {
        this(id, filename, contentType, size, createdAt, DocumentStatus.ACTIVE, 1, null, null, DocumentVisibility.INTERNAL, 0);
    }

    public DocumentInfo(
            String id,
            String filename,
            String contentType,
            long size,
            Instant createdAt,
            DocumentStatus status,
            int version,
            String ownerId,
            String department,
            DocumentVisibility visibility,
            int permissionLevel
    ) {
        this.id = id;
        this.filename = filename;
        this.contentType = contentType;
        this.size = size;
        this.createdAt = createdAt;
        this.status = status;
        this.version = version;
        this.ownerId = ownerId;
        this.department = department;
        this.visibility = visibility;
        this.permissionLevel = permissionLevel;
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
