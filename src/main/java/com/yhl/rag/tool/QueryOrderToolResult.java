package com.yhl.rag.tool;

import java.math.BigDecimal;

public class QueryOrderToolResult {

    private String orderId;

    private String status;

    private BigDecimal amount;

    private String createdAt;

    private String logisticsStatus;

    public QueryOrderToolResult() {
    }

    public QueryOrderToolResult(String orderId, String status, BigDecimal amount, String createdAt, String logisticsStatus) {
        this.orderId = orderId;
        this.status = status;
        this.amount = amount;
        this.createdAt = createdAt;
        this.logisticsStatus = logisticsStatus;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getLogisticsStatus() {
        return logisticsStatus;
    }

    public void setLogisticsStatus(String logisticsStatus) {
        this.logisticsStatus = logisticsStatus;
    }
}
