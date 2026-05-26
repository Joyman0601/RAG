package com.yhl.rag.document;

import java.time.Instant;
import java.util.Set;

public class DocumentInfo {

    private String id;

    private String filename;

    private String contentType;

    private long size;

    private Instant createdAt;

    private String tenantId;

    private String ownerId;

    private String departmentId;

    private DocumentVisibility visibility = DocumentVisibility.DEPARTMENT;

    private Set<String> allowedUserIds = Set.of();

    private Set<String> allowedRoleIds = Set.of();

    private DocumentStatus status = DocumentStatus.READY;

    private int currentVersion = 1;

    private int permissionLevel;

    public DocumentInfo() {
    }

    public DocumentInfo(String id, String filename, String contentType, long size, Instant createdAt) {
        this(id, filename, contentType, size, createdAt, DocumentStatus.READY, 1, null, null, DocumentVisibility.DEPARTMENT, 0);
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
            String departmentId,
            DocumentVisibility visibility,
            int permissionLevel
    ) {
        this(
                id,
                filename,
                contentType,
                size,
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

    public DocumentInfo(
            String id,
            String filename,
            String contentType,
            long size,
            Instant createdAt,
            String tenantId,
            String ownerId,
            String departmentId,
            DocumentVisibility visibility,
            Set<String> allowedUserIds,
            Set<String> allowedRoleIds,
            DocumentStatus status,
            int currentVersion,
            int permissionLevel
    ) {
        this.id = id;
        this.filename = filename;
        this.contentType = contentType;
        this.size = size;
        this.createdAt = createdAt;
        this.tenantId = tenantId;
        this.ownerId = ownerId;
        this.departmentId = departmentId;
        this.visibility = visibility;
        this.allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
        this.allowedRoleIds = allowedRoleIds == null ? Set.of() : Set.copyOf(allowedRoleIds);
        this.status = status;
        this.currentVersion = currentVersion;
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

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    public int getVersion() {
        return currentVersion;
    }

    public void setVersion(int version) {
        this.currentVersion = version;
    }

    public int getPermissionLevel() {
        return permissionLevel;
    }

    public void setPermissionLevel(int permissionLevel) {
        this.permissionLevel = permissionLevel;
    }
}
