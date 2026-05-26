package com.yhl.rag.cost;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cost")
public class CostProperties {

    private int chatMaxInputTokens = 1500;

    private int ragMaxContextTokens = 1200;

    private int ragMaxOutputTokens = 600;

    private int agentMaxSteps = 3;

    private int agentMaxLlmCalls = 4;

    private int userDailyTokenQuota = 100_000;

    private int tenantDailyTokenQuota = 1_000_000;

    private int rateLimitPerMinute = 60;

    private double estimatedCostPerThousandTokens = 0.001;

    public int getChatMaxInputTokens() {
        return chatMaxInputTokens;
    }

    public void setChatMaxInputTokens(int chatMaxInputTokens) {
        this.chatMaxInputTokens = chatMaxInputTokens;
    }

    public int getRagMaxContextTokens() {
        return ragMaxContextTokens;
    }

    public void setRagMaxContextTokens(int ragMaxContextTokens) {
        this.ragMaxContextTokens = ragMaxContextTokens;
    }

    public int getRagMaxOutputTokens() {
        return ragMaxOutputTokens;
    }

    public void setRagMaxOutputTokens(int ragMaxOutputTokens) {
        this.ragMaxOutputTokens = ragMaxOutputTokens;
    }

    public int getAgentMaxSteps() {
        return agentMaxSteps;
    }

    public void setAgentMaxSteps(int agentMaxSteps) {
        this.agentMaxSteps = agentMaxSteps;
    }

    public int getAgentMaxLlmCalls() {
        return agentMaxLlmCalls;
    }

    public void setAgentMaxLlmCalls(int agentMaxLlmCalls) {
        this.agentMaxLlmCalls = agentMaxLlmCalls;
    }

    public int getUserDailyTokenQuota() {
        return userDailyTokenQuota;
    }

    public void setUserDailyTokenQuota(int userDailyTokenQuota) {
        this.userDailyTokenQuota = userDailyTokenQuota;
    }

    public int getTenantDailyTokenQuota() {
        return tenantDailyTokenQuota;
    }

    public void setTenantDailyTokenQuota(int tenantDailyTokenQuota) {
        this.tenantDailyTokenQuota = tenantDailyTokenQuota;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public double getEstimatedCostPerThousandTokens() {
        return estimatedCostPerThousandTokens;
    }

    public void setEstimatedCostPerThousandTokens(double estimatedCostPerThousandTokens) {
        this.estimatedCostPerThousandTokens = estimatedCostPerThousandTokens;
    }
}
