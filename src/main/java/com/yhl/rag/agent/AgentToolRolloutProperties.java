package com.yhl.rag.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.tool-rollout")
public class AgentToolRolloutProperties {

    private Map<String, ToolPolicy> tools = new HashMap<>();

    public Map<String, ToolPolicy> getTools() {
        return tools;
    }

    public void setTools(Map<String, ToolPolicy> tools) {
        this.tools = tools == null ? new HashMap<>() : tools;
    }

    public ToolPolicy policyFor(String toolName) {
        ToolPolicy defaults = defaultPolicy(toolName);
        ToolPolicy configured = tools.get(toolName);
        if (configured == null) {
            return defaults;
        }
        defaults.setEnabled(configured.isEnabled());
        defaults.setShadowOnly(configured.isShadowOnly());
        defaults.setAutoExecute(configured.isAutoExecute());
        defaults.setRequiresConfirmation(configured.isRequiresConfirmation());
        defaults.setAllowedTenantIds(configured.getAllowedTenantIds());
        defaults.setAllowedRoleIds(configured.getAllowedRoleIds());
        defaults.setMaxCallsPerRequest(configured.getMaxCallsPerRequest());
        return defaults;
    }

    private ToolPolicy defaultPolicy(String toolName) {
        ToolPolicy policy = new ToolPolicy();
        if ("cancel_order".equals(toolName)) {
            policy.setAutoExecute(false);
            policy.setRequiresConfirmation(true);
            policy.setMaxCallsPerRequest(1);
        }
        return policy;
    }

    public static class ToolPolicy {

        private boolean enabled = true;

        private boolean shadowOnly = false;

        private boolean autoExecute = true;

        private boolean requiresConfirmation = false;

        private Set<String> allowedTenantIds = new HashSet<>();

        private Set<String> allowedRoleIds = new HashSet<>();

        private int maxCallsPerRequest = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isShadowOnly() {
            return shadowOnly;
        }

        public void setShadowOnly(boolean shadowOnly) {
            this.shadowOnly = shadowOnly;
        }

        public boolean isAutoExecute() {
            return autoExecute;
        }

        public void setAutoExecute(boolean autoExecute) {
            this.autoExecute = autoExecute;
        }

        public boolean isRequiresConfirmation() {
            return requiresConfirmation;
        }

        public void setRequiresConfirmation(boolean requiresConfirmation) {
            this.requiresConfirmation = requiresConfirmation;
        }

        public Set<String> getAllowedTenantIds() {
            return allowedTenantIds;
        }

        public void setAllowedTenantIds(Set<String> allowedTenantIds) {
            this.allowedTenantIds = allowedTenantIds == null ? new HashSet<>() : new HashSet<>(allowedTenantIds);
        }

        public Set<String> getAllowedRoleIds() {
            return allowedRoleIds;
        }

        public void setAllowedRoleIds(Set<String> allowedRoleIds) {
            this.allowedRoleIds = allowedRoleIds == null ? new HashSet<>() : new HashSet<>(allowedRoleIds);
        }

        public int getMaxCallsPerRequest() {
            return maxCallsPerRequest;
        }

        public void setMaxCallsPerRequest(int maxCallsPerRequest) {
            this.maxCallsPerRequest = maxCallsPerRequest;
        }
    }
}
