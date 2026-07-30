package com.yhl.rag.agent;

public class AgentToolDebugInfo {

    private String toolName;

    private boolean shadowOnly;

    private boolean rolloutBlocked;

    private boolean requiresConfirmation;

    private String blockedReason;

    public AgentToolDebugInfo() {
    }

    public AgentToolDebugInfo(String toolName, boolean shadowOnly, boolean rolloutBlocked, boolean requiresConfirmation, String blockedReason) {
        this.toolName = toolName;
        this.shadowOnly = shadowOnly;
        this.rolloutBlocked = rolloutBlocked;
        this.requiresConfirmation = requiresConfirmation;
        this.blockedReason = blockedReason;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public boolean isShadowOnly() {
        return shadowOnly;
    }

    public void setShadowOnly(boolean shadowOnly) {
        this.shadowOnly = shadowOnly;
    }

    public boolean isRolloutBlocked() {
        return rolloutBlocked;
    }

    public void setRolloutBlocked(boolean rolloutBlocked) {
        this.rolloutBlocked = rolloutBlocked;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }
}
