package com.yhl.rag.agent;

import java.util.Set;

import com.yhl.rag.tool.ToolExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

@Service
public class AgentToolRolloutService {

    private final AgentToolRolloutProperties properties;

    @Autowired
    public AgentToolRolloutService(AgentToolRolloutProperties properties) {
        this.properties = properties;
    }

    public AgentToolRolloutService() {
        this(new AgentToolRolloutProperties());
    }

    public boolean isVisible(String toolName, ToolExecutionContext context) {
        return visibilityDecision(toolName, context).getPolicyDecision() != ShadowToolPolicyDecision.BLOCKED;
    }

    public AgentToolRolloutDecision visibilityDecision(String toolName, ToolExecutionContext context) {
        AgentToolRolloutProperties.ToolPolicy policy = properties.policyFor(toolName);
        if (!policy.isEnabled()) {
            return AgentToolRolloutDecision.blocked("TOOL_DISABLED");
        }
        if (!isTenantAllowed(policy.getAllowedTenantIds(), context)) {
            return AgentToolRolloutDecision.blocked("TENANT_NOT_ALLOWED");
        }
        if (!isRoleAllowed(policy.getAllowedRoleIds(), context)) {
            return AgentToolRolloutDecision.blocked("ROLE_NOT_ALLOWED");
        }
        return AgentToolRolloutDecision.allow();
    }

    public AgentToolRolloutDecision evaluate(String toolName, ToolExecutionContext context, int callsInRequest) {
        AgentToolRolloutDecision visibilityDecision = visibilityDecision(toolName, context);
        if (visibilityDecision.isRolloutBlocked()) {
            return visibilityDecision;
        }

        AgentToolRolloutProperties.ToolPolicy policy = properties.policyFor(toolName);
        if (policy.getMaxCallsPerRequest() > 0 && callsInRequest > policy.getMaxCallsPerRequest()) {
            return AgentToolRolloutDecision.maxCallsExceeded("MAX_CALLS_PER_REQUEST_EXCEEDED");
        }
        if (policy.isShadowOnly()) {
            return AgentToolRolloutDecision.shadowOnly();
        }
        if (policy.isRequiresConfirmation() || !policy.isAutoExecute()) {
            return AgentToolRolloutDecision.confirmationRequired(policy.isRequiresConfirmation() ? "REQUIRES_CONFIRMATION" : "AUTO_EXECUTE_DISABLED");
        }
        return AgentToolRolloutDecision.allow();
    }

    private boolean isTenantAllowed(Set<String> allowedTenantIds, ToolExecutionContext context) {
        if (CollectionUtils.isEmpty(allowedTenantIds)) {
            return true;
        }
        return context != null && allowedTenantIds.contains(context.getTenantId());
    }

    private boolean isRoleAllowed(Set<String> allowedRoleIds, ToolExecutionContext context) {
        if (CollectionUtils.isEmpty(allowedRoleIds)) {
            return true;
        }
        if (context == null || CollectionUtils.isEmpty(context.getRoles())) {
            return false;
        }
        for (String role : context.getRoles()) {
            if (allowedRoleIds.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
