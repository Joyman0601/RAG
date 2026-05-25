package com.yhl.rag.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.safety")
public class AgentSafetyPolicy {

    private int maxInputLength = 2000;

    private int maxOutputTokens = 800;

    private int maxAgentSteps = 3;

    private long maxAgentDurationMs = 10_000;

    private boolean allowHighRiskAutoExecute = false;

    private boolean requireToolPermissionCode = true;

    private boolean requireToolRiskLevel = true;

    private boolean requireConfirmationForHighRisk = true;

    private boolean logFullPrompt = false;

    public int getMaxInputLength() {
        return maxInputLength;
    }

    public void setMaxInputLength(int maxInputLength) {
        this.maxInputLength = maxInputLength;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getMaxAgentSteps() {
        return maxAgentSteps;
    }

    public void setMaxAgentSteps(int maxAgentSteps) {
        this.maxAgentSteps = maxAgentSteps;
    }

    public long getMaxAgentDurationMs() {
        return maxAgentDurationMs;
    }

    public void setMaxAgentDurationMs(long maxAgentDurationMs) {
        this.maxAgentDurationMs = maxAgentDurationMs;
    }

    public boolean isAllowHighRiskAutoExecute() {
        return allowHighRiskAutoExecute;
    }

    public void setAllowHighRiskAutoExecute(boolean allowHighRiskAutoExecute) {
        this.allowHighRiskAutoExecute = allowHighRiskAutoExecute;
    }

    public boolean isRequireToolPermissionCode() {
        return requireToolPermissionCode;
    }

    public void setRequireToolPermissionCode(boolean requireToolPermissionCode) {
        this.requireToolPermissionCode = requireToolPermissionCode;
    }

    public boolean isRequireToolRiskLevel() {
        return requireToolRiskLevel;
    }

    public void setRequireToolRiskLevel(boolean requireToolRiskLevel) {
        this.requireToolRiskLevel = requireToolRiskLevel;
    }

    public boolean isRequireConfirmationForHighRisk() {
        return requireConfirmationForHighRisk;
    }

    public void setRequireConfirmationForHighRisk(boolean requireConfirmationForHighRisk) {
        this.requireConfirmationForHighRisk = requireConfirmationForHighRisk;
    }

    public boolean isLogFullPrompt() {
        return logFullPrompt;
    }

    public void setLogFullPrompt(boolean logFullPrompt) {
        this.logFullPrompt = logFullPrompt;
    }
}
