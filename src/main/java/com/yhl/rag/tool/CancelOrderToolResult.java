package com.yhl.rag.tool;

public class CancelOrderToolResult {

    private String orderId;

    private String status;

    private String cancelledAt;

    public CancelOrderToolResult() {
    }

    public CancelOrderToolResult(String orderId, String status, String cancelledAt) {
        this.orderId = orderId;
        this.status = status;
        this.cancelledAt = cancelledAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(String cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
}
