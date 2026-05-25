package com.yhl.rag.agent;

import org.springframework.stereotype.Component;

@Component
public class AgentLoopConfig {

    private int maxSteps = 3;

    private long maxDurationMs = 10_000;

    private boolean allowHighRiskTools = false;

    private int maxRepeatedToolCalls = 1;

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    public void setMaxDurationMs(long maxDurationMs) {
        this.maxDurationMs = maxDurationMs;
    }

    public boolean isAllowHighRiskTools() {
        return allowHighRiskTools;
    }

    public void setAllowHighRiskTools(boolean allowHighRiskTools) {
        this.allowHighRiskTools = allowHighRiskTools;
    }

    public int getMaxRepeatedToolCalls() {
        return maxRepeatedToolCalls;
    }

    public void setMaxRepeatedToolCalls(int maxRepeatedToolCalls) {
        this.maxRepeatedToolCalls = maxRepeatedToolCalls;
    }
}
