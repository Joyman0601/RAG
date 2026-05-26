package com.yhl.rag.cost;

import java.time.Instant;

public class UsageRecord {

    private String requestId;

    private String tenantId;

    private String userId;

    private String interfaceName;

    private String model;

    private int promptTokens;

    private int completionTokens;

    private int totalTokens;

    private double estimatedCost;

    private long latencyMs;

    private boolean success;

    private Instant createdAt;

    public UsageRecord() {
    }

    public UsageRecord(
            String requestId,
            String tenantId,
            String userId,
            String interfaceName,
            String model,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            double estimatedCost,
            long latencyMs,
            boolean success,
            Instant createdAt
    ) {
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.interfaceName = interfaceName;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.estimatedCost = estimatedCost;
        this.latencyMs = latencyMs;
        this.success = success;
        this.createdAt = createdAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getModel() {
        return model;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public double getEstimatedCost() {
        return estimatedCost;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
