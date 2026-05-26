package com.yhl.rag.vector;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yhl.rag.document.DocumentStatus;
import com.yhl.rag.document.DocumentVisibility;

public class VectorSearchRequest {

    private List<Double> queryVector;

    private int topK;

    private double scoreThreshold;

    private boolean includeBelowThreshold;

    private String tenantId;

    private String userId;

    private String department;

    private Set<String> departmentIds = Set.of();

    private Set<String> roleIds = Set.of();

    private DocumentVisibility visibility;

    private DocumentStatus status;

    private DocumentStatus documentStatus;

    private Integer version;

    private Map<String, Integer> documentVersions;

    public List<Double> getQueryVector() {
        return queryVector;
    }

    public void setQueryVector(List<Double> queryVector) {
        this.queryVector = queryVector;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getScoreThreshold() {
        return scoreThreshold;
    }

    public void setScoreThreshold(double scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    public boolean isIncludeBelowThreshold() {
        return includeBelowThreshold;
    }

    public void setIncludeBelowThreshold(boolean includeBelowThreshold) {
        this.includeBelowThreshold = includeBelowThreshold;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
        this.departmentIds = department == null ? Set.of() : Set.of(department);
    }

    public Set<String> getDepartmentIds() {
        return departmentIds;
    }

    public void setDepartmentIds(Set<String> departmentIds) {
        this.departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
    }

    public Set<String> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(Set<String> roleIds) {
        this.roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
    }

    public DocumentVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(DocumentVisibility visibility) {
        this.visibility = visibility;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Map<String, Integer> getDocumentVersions() {
        return documentVersions;
    }

    public void setDocumentVersions(Map<String, Integer> documentVersions) {
        this.documentVersions = documentVersions;
    }
}
