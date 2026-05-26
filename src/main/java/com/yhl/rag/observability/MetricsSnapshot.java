package com.yhl.rag.observability;

public class MetricsSnapshot {

    private long apiRequestCount;

    private long apiErrorCount;

    private long llmCallCount;

    private long llmErrorCount;

    private long totalTokens;

    private long ragNoAnswerCount;

    private long agentMaxStepsCount;

    private long toolFailureCount;

    public MetricsSnapshot(
            long apiRequestCount,
            long apiErrorCount,
            long llmCallCount,
            long llmErrorCount,
            long totalTokens,
            long ragNoAnswerCount,
            long agentMaxStepsCount,
            long toolFailureCount
    ) {
        this.apiRequestCount = apiRequestCount;
        this.apiErrorCount = apiErrorCount;
        this.llmCallCount = llmCallCount;
        this.llmErrorCount = llmErrorCount;
        this.totalTokens = totalTokens;
        this.ragNoAnswerCount = ragNoAnswerCount;
        this.agentMaxStepsCount = agentMaxStepsCount;
        this.toolFailureCount = toolFailureCount;
    }

    public long getApiRequestCount() {
        return apiRequestCount;
    }

    public long getApiErrorCount() {
        return apiErrorCount;
    }

    public long getLlmCallCount() {
        return llmCallCount;
    }

    public long getLlmErrorCount() {
        return llmErrorCount;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public long getRagNoAnswerCount() {
        return ragNoAnswerCount;
    }

    public long getAgentMaxStepsCount() {
        return agentMaxStepsCount;
    }

    public long getToolFailureCount() {
        return toolFailureCount;
    }

    public double getLlmErrorRate() {
        return llmCallCount == 0 ? 0.0 : (double) llmErrorCount / llmCallCount;
    }

    public double getRagNoAnswerRate() {
        return apiRequestCount == 0 ? 0.0 : (double) ragNoAnswerCount / apiRequestCount;
    }
}
