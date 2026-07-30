package com.yhl.rag.agent;

public class AgentToolRolloutDecision {

    private final ShadowToolPolicyDecision policyDecision;

    private final String blockedReason;

    private final boolean shadowOnly;

    private final boolean requiresConfirmation;

    private final boolean rolloutBlocked;

    private AgentToolRolloutDecision(
            ShadowToolPolicyDecision policyDecision,
            String blockedReason,
            boolean shadowOnly,
            boolean requiresConfirmation,
            boolean rolloutBlocked
    ) {
        this.policyDecision = policyDecision;
        this.blockedReason = blockedReason;
        this.shadowOnly = shadowOnly;
        this.requiresConfirmation = requiresConfirmation;
        this.rolloutBlocked = rolloutBlocked;
    }

    public static AgentToolRolloutDecision allow() {
        return new AgentToolRolloutDecision(ShadowToolPolicyDecision.ALLOW_EXECUTE, null, false, false, false);
    }

    public static AgentToolRolloutDecision shadowOnly() {
        return new AgentToolRolloutDecision(ShadowToolPolicyDecision.SHADOW_ONLY, null, true, false, false);
    }

    public static AgentToolRolloutDecision confirmationRequired(String reason) {
        return new AgentToolRolloutDecision(ShadowToolPolicyDecision.CONFIRMATION_REQUIRED, reason, false, true, false);
    }

    public static AgentToolRolloutDecision blocked(String reason) {
        return new AgentToolRolloutDecision(ShadowToolPolicyDecision.BLOCKED, reason, false, false, true);
    }

    public static AgentToolRolloutDecision maxCallsExceeded(String reason) {
        return new AgentToolRolloutDecision(ShadowToolPolicyDecision.MAX_CALLS_EXCEEDED, reason, false, false, true);
    }

    public ShadowToolPolicyDecision getPolicyDecision() {
        return policyDecision;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public boolean isShadowOnly() {
        return shadowOnly;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public boolean isRolloutBlocked() {
        return rolloutBlocked;
    }
}
