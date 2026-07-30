package com.yhl.rag.agent;

import java.time.Instant;

public class RefundWorkflowSession {

    private String workflowId;

    private String conversationId;

    private String userId;

    private RefundWorkflowState state;

    private String orderId;

    private String orderSummary;

    private String policySummary;

    private Boolean eligible;

    private String rejectReason;

    private String confirmationId;

    private String requestId;

    private Instant createdAt;

    private Instant updatedAt;

    public RefundWorkflowSession() {
    }

    public RefundWorkflowSession(String workflowId, String conversationId, String userId) {
        Instant now = Instant.now();
        this.workflowId = workflowId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.state = RefundWorkflowState.INIT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
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

    public RefundWorkflowState getState() {
        return state;
    }

    public void setState(RefundWorkflowState state) {
        this.state = state;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderSummary() {
        return orderSummary;
    }

    public void setOrderSummary(String orderSummary) {
        this.orderSummary = orderSummary;
    }

    public String getPolicySummary() {
        return policySummary;
    }

    public void setPolicySummary(String policySummary) {
        this.policySummary = policySummary;
    }

    public Boolean getEligible() {
        return eligible;
    }

    public void setEligible(Boolean eligible) {
        this.eligible = eligible;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getConfirmationId() {
        return confirmationId;
    }

    public void setConfirmationId(String confirmationId) {
        this.confirmationId = confirmationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
