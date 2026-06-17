package com.yhl.rag.cost;

import java.time.Instant;
import java.util.List;

import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.observability.MetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CostGovernanceService {

    private final CostProperties costProperties;

    private final TokenEstimator tokenEstimator;

    private final QuotaService quotaService;

    private final RateLimitService rateLimitService;

    private final UsageRecordService usageRecordService;

    private final LlmProperties llmProperties;

    private final MetricsService metricsService;

    @Autowired
    public CostGovernanceService(
            CostProperties costProperties,
            TokenEstimator tokenEstimator,
            QuotaService quotaService,
            RateLimitService rateLimitService,
            UsageRecordService usageRecordService,
            LlmProperties llmProperties,
            MetricsService metricsService
    ) {
        this.costProperties = costProperties;
        this.tokenEstimator = tokenEstimator;
        this.quotaService = quotaService;
        this.rateLimitService = rateLimitService;
        this.usageRecordService = usageRecordService;
        this.llmProperties = llmProperties;
        this.metricsService = metricsService;
    }

    public CostGovernanceService(
            CostProperties costProperties,
            TokenEstimator tokenEstimator,
            QuotaService quotaService,
            RateLimitService rateLimitService,
            UsageRecordService usageRecordService,
            LlmProperties llmProperties
    ) {
        this(
                costProperties,
                tokenEstimator,
                quotaService,
                rateLimitService,
                usageRecordService,
                llmProperties,
                new MetricsService()
        );
    }

    public int checkBeforeLlm(
            String tenantId,
            String userId,
            String interfaceName,
            String instructions,
            List<LlmMessage> messages,
            int maxInputTokens,
            int reservedOutputTokens
    ) {
        rateLimitService.checkRateLimit(userId, interfaceName, costProperties.getRateLimitPerMinute());
        int promptTokens = tokenEstimator.estimateMessages(instructions, messages);
        if (promptTokens > maxInputTokens) {
            throw new CostException(
                    CostErrorCode.TOKEN_BUDGET_EXCEEDED,
                    "输入 token 预算超限，当前约 " + promptTokens + "，上限 " + maxInputTokens
            );
        }
        quotaService.checkQuota(tenantId, userId, promptTokens + Math.max(0, reservedOutputTokens), costProperties);
        return promptTokens;
    }

    public UsageRecord recordUsage(
            String requestId,
            String tenantId,
            String userId,
            String interfaceName,
            ModelTier modelTier,
            String instructions,
            List<LlmMessage> messages,
            LlmGenerationResult result,
            long latencyMs,
            boolean success
    ) {
        int estimatedPrompt = tokenEstimator.estimateMessages(instructions, messages);
        int estimatedCompletion = tokenEstimator.estimate(result == null ? null : result.getAnswer());
        int promptTokens = valueOr(result == null ? null : result.getPromptTokens(), estimatedPrompt);
        int completionTokens = valueOr(result == null ? null : result.getCompletionTokens(), estimatedCompletion);
        int totalTokens = valueOr(result == null ? null : result.getTotalTokens(), promptTokens + completionTokens);
        int cachedTokens = Math.max(0, result == null || result.getCachedTokens() == null ? 0 : result.getCachedTokens());
        UsageRecord usageRecord = new UsageRecord(
                requestId,
                tenantId,
                userId,
                interfaceName,
                modelName(modelTier),
                promptTokens,
                completionTokens,
                totalTokens,
                cachedTokens,
                totalTokens / 1000.0 * costProperties.getEstimatedCostPerThousandTokens(),
                latencyMs,
                success,
                Instant.now()
        );
        usageRecordService.record(usageRecord);
        quotaService.addUsage(tenantId, userId, totalTokens);
        metricsService.recordLlmCall(totalTokens, success);
        return usageRecord;
    }

    public CostProperties properties() {
        return costProperties;
    }

    public TokenEstimator tokenEstimator() {
        return tokenEstimator;
    }

    private String modelName(ModelTier modelTier) {
        String configuredModel = modelTier == ModelTier.EMBEDDING
                ? llmProperties.getEmbeddingModel()
                : llmProperties.getModel();
        return modelTier.name() + ":" + (configuredModel == null ? "default" : configuredModel);
    }

    private static int valueOr(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
