package com.yhl.rag.agent;

import java.time.Instant;

import com.yhl.rag.tool.RiskLevel;

public class ShadowToolDecision {

    private String requestId;

    private String tenantId;

    private String userId;

    private String toolName;

    private String argumentsHash;

    private String validationResult;

    private RiskLevel riskLevel;

    private ShadowToolPolicyDecision policyDecision;

    private String blockedReason;

    private String model;

    private long latencyMs;

    private Instant createdAt;

    public ShadowToolDecision() {
    }

    public ShadowToolDecision(
            String requestId,
            String tenantId,
            String userId,
            String toolName,
            String argumentsHash,
            String validationResult,
            RiskLevel riskLevel,
            ShadowToolPolicyDecision policyDecision,
            String blockedReason,
            String model,
            long latencyMs,
            Instant createdAt
    ) {
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.toolName = toolName;
        this.argumentsHash = argumentsHash;
        this.validationResult = validationResult;
        this.riskLevel = riskLevel;
        this.policyDecision = policyDecision;
        this.blockedReason = blockedReason;
        this.model = model;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArgumentsHash() {
        return argumentsHash;
    }

    public void setArgumentsHash(String argumentsHash) {
        this.argumentsHash = argumentsHash;
    }

    public String getValidationResult() {
        return validationResult;
    }

    public void setValidationResult(String validationResult) {
        this.validationResult = validationResult;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public ShadowToolPolicyDecision getPolicyDecision() {
        return policyDecision;
    }

    public void setPolicyDecision(ShadowToolPolicyDecision policyDecision) {
        this.policyDecision = policyDecision;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
