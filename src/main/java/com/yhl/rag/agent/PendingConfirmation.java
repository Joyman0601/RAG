package com.yhl.rag.agent;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.yhl.rag.tool.RiskLevel;

public class PendingConfirmation {

    private String confirmationId;

    private String userId;

    private String toolName;

    private JsonNode validatedArguments;

    private RiskLevel riskLevel;

    private String summary;

    private PendingConfirmationStatus status;

    private Instant createdAt;

    private Instant expiresAt;

    private String requestId;

    public String getConfirmationId() {
        return confirmationId;
    }

    public void setConfirmationId(String confirmationId) {
        this.confirmationId = confirmationId;
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

    public JsonNode getValidatedArguments() {
        return validatedArguments;
    }

    public void setValidatedArguments(JsonNode validatedArguments) {
        this.validatedArguments = validatedArguments;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public PendingConfirmationStatus getStatus() {
        return status;
    }

    public void setStatus(PendingConfirmationStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
