package com.yhl.rag.vector;

import java.util.List;

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

    private DocumentVisibility visibility;

    private DocumentStatus status;

    private Integer version;

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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
