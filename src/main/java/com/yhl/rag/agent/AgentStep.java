package com.yhl.rag.agent;

import java.time.Instant;

public class AgentStep {

    private String requestId;

    private String conversationId;

    private int stepIndex;

    private AgentActionType actionType;

    private String toolName;

    private String argumentsSummary;

    private boolean success;

    private String errorCode;

    private long elapsedMs;

    private String stopReason;

    private Instant createdAt;

    public AgentStep() {
    }

    public AgentStep(int stepIndex, AgentActionType actionType, String toolName, String argumentsSummary, boolean success, String errorCode, long elapsedMs) {
        this(null, null, stepIndex, actionType, toolName, argumentsSummary, success, errorCode, elapsedMs, null);
    }

    public AgentStep(
            String requestId,
            String conversationId,
            int stepIndex,
            AgentActionType actionType,
            String toolName,
            String argumentsSummary,
            boolean success,
            String errorCode,
            long elapsedMs,
            String stopReason
    ) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.stepIndex = stepIndex;
        this.actionType = actionType;
        this.toolName = toolName;
        this.argumentsSummary = argumentsSummary;
        this.success = success;
        this.errorCode = errorCode;
        this.elapsedMs = elapsedMs;
        this.stopReason = stopReason;
        this.createdAt = Instant.now();
    }

    public static AgentStep of(
            ToolExecutionContextSnapshot context,
            int stepIndex,
            AgentActionType actionType,
            String toolName,
            String argumentsSummary,
            boolean success,
            String errorCode,
            long elapsedMs,
            String stopReason
    ) {
        return new AgentStep(
                context.requestId(),
                context.conversationId(),
                stepIndex,
                actionType,
                toolName,
                argumentsSummary,
                success,
                errorCode,
                elapsedMs,
                stopReason
        );
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    public AgentActionType getActionType() {
        return actionType;
    }

    public void setActionType(AgentActionType actionType) {
        this.actionType = actionType;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArgumentsSummary() {
        return argumentsSummary;
    }

    public void setArgumentsSummary(String argumentsSummary) {
        this.argumentsSummary = argumentsSummary;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public record ToolExecutionContextSnapshot(String requestId, String conversationId) {
    }
}
