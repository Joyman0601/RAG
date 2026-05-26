package com.yhl.rag.rag;

public class RagEvalSummary {

    private int total;

    private double averageHitAtK;

    private double averageRecallAtK;

    private double averageMrr;

    private double averageLatencyMs;

    private int totalPromptTokens;

    private int totalCompletionTokens;

    private int totalTokens;

    public RagEvalSummary() {
    }

    public RagEvalSummary(
            int total,
            double averageHitAtK,
            double averageRecallAtK,
            double averageMrr,
            double averageLatencyMs,
            int totalPromptTokens,
            int totalCompletionTokens,
            int totalTokens
    ) {
        this.total = total;
        this.averageHitAtK = averageHitAtK;
        this.averageRecallAtK = averageRecallAtK;
        this.averageMrr = averageMrr;
        this.averageLatencyMs = averageLatencyMs;
        this.totalPromptTokens = totalPromptTokens;
        this.totalCompletionTokens = totalCompletionTokens;
        this.totalTokens = totalTokens;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public double getAverageHitAtK() {
        return averageHitAtK;
    }

    public void setAverageHitAtK(double averageHitAtK) {
        this.averageHitAtK = averageHitAtK;
    }

    public double getAverageRecallAtK() {
        return averageRecallAtK;
    }

    public void setAverageRecallAtK(double averageRecallAtK) {
        this.averageRecallAtK = averageRecallAtK;
    }

    public double getAverageMrr() {
        return averageMrr;
    }

    public void setAverageMrr(double averageMrr) {
        this.averageMrr = averageMrr;
    }

    public double getAverageLatencyMs() {
        return averageLatencyMs;
    }

    public void setAverageLatencyMs(double averageLatencyMs) {
        this.averageLatencyMs = averageLatencyMs;
    }

    public int getTotalPromptTokens() {
        return totalPromptTokens;
    }

    public void setTotalPromptTokens(int totalPromptTokens) {
        this.totalPromptTokens = totalPromptTokens;
    }

    public int getTotalCompletionTokens() {
        return totalCompletionTokens;
    }

    public void setTotalCompletionTokens(int totalCompletionTokens) {
        this.totalCompletionTokens = totalCompletionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }
}
