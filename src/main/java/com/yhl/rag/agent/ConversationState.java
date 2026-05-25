package com.yhl.rag.agent;

import java.time.Instant;

public class ConversationState {

    private String conversationId;

    private String userId;

    private String currentOrderId;

    private String pendingConfirmationId;

    private String lastToolName;

    private String lastToolResultSummary;

    private Instant updatedAt;

    public ConversationState() {
    }

    public ConversationState(String conversationId, String userId) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCurrentOrderId() {
        return currentOrderId;
    }

    public void setCurrentOrderId(String currentOrderId) {
        this.currentOrderId = currentOrderId;
    }

    public String getPendingConfirmationId() {
        return pendingConfirmationId;
    }

    public void setPendingConfirmationId(String pendingConfirmationId) {
        this.pendingConfirmationId = pendingConfirmationId;
    }

    public String getLastToolName() {
        return lastToolName;
    }

    public void setLastToolName(String lastToolName) {
        this.lastToolName = lastToolName;
    }

    public String getLastToolResultSummary() {
        return lastToolResultSummary;
    }

    public void setLastToolResultSummary(String lastToolResultSummary) {
        this.lastToolResultSummary = lastToolResultSummary;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
