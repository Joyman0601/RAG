package com.yhl.rag.chunk;

import java.util.Set;

import com.yhl.rag.document.DocumentVisibility;

/**
 * 独立父块：子块只存 parentId，父块正文存这里。检索命中子块后按 parentId 回填父块给 LLM。
 * 权限/版本元数据与子块对齐，便于 pgvector 后端做同样的租户与可见性过滤。
 */
public class ParentBlock {

    private String parentId;
    private String documentId;
    private String content;
    private int version;
    private String tenantId;
    private String ownerId;
    private String departmentId;
    private DocumentVisibility visibility = DocumentVisibility.DEPARTMENT;
    private Set<String> allowedUserIds = Set.of();
    private Set<String> allowedRoleIds = Set.of();
    private int permissionLevel;

    public ParentBlock() {
    }

    public ParentBlock(
            String parentId,
            String documentId,
            String content,
            int version,
            String tenantId,
            String ownerId,
            String departmentId,
            DocumentVisibility visibility,
            Set<String> allowedUserIds,
            Set<String> allowedRoleIds,
            int permissionLevel
    ) {
        this.parentId = parentId;
        this.documentId = documentId;
        this.content = content;
        this.version = version;
        this.tenantId = tenantId;
        this.ownerId = ownerId;
        this.departmentId = departmentId;
        this.visibility = visibility == null ? DocumentVisibility.DEPARTMENT : visibility;
        this.allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
        this.allowedRoleIds = allowedRoleIds == null ? Set.of() : Set.copyOf(allowedRoleIds);
        this.permissionLevel = permissionLevel;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
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

    public int getPermissionLevel() {
        return permissionLevel;
    }

    public void setPermissionLevel(int permissionLevel) {
        this.permissionLevel = permissionLevel;
    }
}
