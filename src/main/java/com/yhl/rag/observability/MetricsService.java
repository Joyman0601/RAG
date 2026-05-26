package com.yhl.rag.observability;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

@Service
public class MetricsService {

    private final AtomicLong apiRequestCount = new AtomicLong();

    private final AtomicLong apiErrorCount = new AtomicLong();

    private final AtomicLong llmCallCount = new AtomicLong();

    private final AtomicLong llmErrorCount = new AtomicLong();

    private final AtomicLong totalTokens = new AtomicLong();

    private final AtomicLong ragNoAnswerCount = new AtomicLong();

    private final AtomicLong agentMaxStepsCount = new AtomicLong();

    private final AtomicLong toolFailureCount = new AtomicLong();

    public void recordApiRequest(int status) {
        apiRequestCount.incrementAndGet();
        if (status >= 400) {
            apiErrorCount.incrementAndGet();
        }
    }

    public void recordLlmCall(int tokens, boolean success) {
        llmCallCount.incrementAndGet();
        totalTokens.addAndGet(Math.max(0, tokens));
        if (!success) {
            llmErrorCount.incrementAndGet();
        }
    }

    public void recordRagNoAnswer() {
        ragNoAnswerCount.incrementAndGet();
    }

    public void recordAgentMaxSteps() {
        agentMaxStepsCount.incrementAndGet();
    }

    public void recordToolFailure() {
        toolFailureCount.incrementAndGet();
    }

    public MetricsSnapshot snapshot() {
        return new MetricsSnapshot(
                apiRequestCount.get(),
                apiErrorCount.get(),
                llmCallCount.get(),
                llmErrorCount.get(),
                totalTokens.get(),
                ragNoAnswerCount.get(),
                agentMaxStepsCount.get(),
                toolFailureCount.get()
        );
    }

    public void clear() {
        apiRequestCount.set(0);
        apiErrorCount.set(0);
        llmCallCount.set(0);
        llmErrorCount.set(0);
        totalTokens.set(0);
        ragNoAnswerCount.set(0);
        agentMaxStepsCount.set(0);
        toolFailureCount.set(0);
    }
}
