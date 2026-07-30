package com.yhl.rag.document;

import java.time.Instant;
import java.util.Set;

public class DocumentChunk {

    private String chunkId;

    private String documentId;

    private String filename;

    private String content;

    private String contentHash;

    private int chunkIndex;

    private Instant createdAt;

    private String tenantId;

    private String ownerId;

    private String departmentId;

    private DocumentVisibility visibility = DocumentVisibility.DEPARTMENT;

    private Set<String> allowedUserIds = Set.of();

    private Set<String> allowedRoleIds = Set.of();

    private DocumentStatus status = DocumentStatus.ACTIVE;

    private DocumentStatus documentStatus = DocumentStatus.READY;

    private int version = 1;

    private int permissionLevel;

    // 独立父块外键：null = 无父块（FIXED/SEMANTIC），非空 = 命中后按它回填 ParentStore 中的父块正文。
    private String parentId;

    // 多模态：默认 TEXT（纯文本路径零回归）；IMAGE 表示向量来自图像本身，content 存说明/alt 供展示。
    private Modality modality = Modality.TEXT;

    // IMAGE chunk 的图片引用（ImageStore 的 key / 对象存储 objectKey）；TEXT chunk 为 null。
    private String imageRef;

    public DocumentChunk() {
    }

    public DocumentChunk(String chunkId, String documentId, String filename, String content, int chunkIndex, Instant createdAt) {
        this(chunkId, documentId, filename, content, null, chunkIndex, createdAt, DocumentStatus.ACTIVE, 1, null, null, DocumentVisibility.DEPARTMENT, 0);
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
            String departmentId,
            DocumentVisibility visibility,
            int permissionLevel
    ) {
        this(
                chunkId,
                documentId,
                filename,
                content,
                contentHash,
                chunkIndex,
                createdAt,
                "tenant-default",
                ownerId,
                departmentId,
                visibility,
                Set.of(),
                Set.of(),
                status,
                version,
                permissionLevel
        );
    }

    public DocumentChunk(
            String chunkId,
            String documentId,
            String filename,
            String content,
            String contentHash,
            int chunkIndex,
            Instant createdAt,
            String tenantId,
            String ownerId,
            String departmentId,
            DocumentVisibility visibility,
            Set<String> allowedUserIds,
            Set<String> allowedRoleIds,
            DocumentStatus status,
            int version,
            int permissionLevel
    ) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.filename = filename;
        this.content = content;
        this.contentHash = contentHash;
        this.chunkIndex = chunkIndex;
        this.createdAt = createdAt;
        this.tenantId = tenantId;
        this.ownerId = ownerId;
        this.departmentId = departmentId;
        this.visibility = visibility;
        this.allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
        this.allowedRoleIds = allowedRoleIds == null ? Set.of() : Set.copyOf(allowedRoleIds);
        this.status = status;
        this.version = version;
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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }

    public String getDepartment() {
        return departmentId;
    }

    public void setDepartment(String department) {
        this.departmentId = department;
    }

    public DocumentVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(DocumentVisibility visibility) {
        this.visibility = visibility;
    }

    public Set<String> getAllowedUserIds() {
        return allowedUserIds;
    }

    public void setAllowedUserIds(Set<String> allowedUserIds) {
        this.allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
    }

    public Set<String> getAllowedRoleIds() {
        return allowedRoleIds;
    }

    public void setAllowedRoleIds(Set<String> allowedRoleIds) {
        this.allowedRoleIds = allowedRoleIds == null ? Set.of() : Set.copyOf(allowedRoleIds);
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public DocumentStatus getDocumentStatus() {
        return documentStatus;
    }

    public void setDocumentStatus(DocumentStatus documentStatus) {
        this.documentStatus = documentStatus;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getPermissionLevel() {
        return permissionLevel;
    }

    public void setPermissionLevel(int permissionLevel) {
        this.permissionLevel = permissionLevel;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public Modality getModality() {
        return modality;
    }

    public void setModality(Modality modality) {
        this.modality = modality == null ? Modality.TEXT : modality;
    }

    public String getImageRef() {
        return imageRef;
    }

    public void setImageRef(String imageRef) {
        this.imageRef = imageRef;
    }
}
