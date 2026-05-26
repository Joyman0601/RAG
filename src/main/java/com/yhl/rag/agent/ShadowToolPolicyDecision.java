package com.yhl.rag.agent;

public enum ShadowToolPolicyDecision {
    ALLOW_EXECUTE,
    SHADOW_ONLY,
    CONFIRMATION_REQUIRED,
    BLOCKED,
    MAX_CALLS_EXCEEDED
}
