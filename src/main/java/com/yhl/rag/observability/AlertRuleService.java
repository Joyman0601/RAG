package com.yhl.rag.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AlertRuleService {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleService.class);

    private double maxLlmErrorRate = 0.2;

    private double maxRagNoAnswerRate = 0.3;

    private long maxAgentMaxStepsCount = 5;

    private long maxTotalTokens = 100_000;

    public void evaluate(MetricsSnapshot snapshot) {
        if (snapshot.getLlmCallCount() > 0 && snapshot.getLlmErrorRate() > maxLlmErrorRate) {
            log.warn("alert_triggered type=LLM_ERROR_RATE llmErrorRate={} llmCallCount={} llmErrorCount={}",
                    snapshot.getLlmErrorRate(),
                    snapshot.getLlmCallCount(),
                    snapshot.getLlmErrorCount());
        }
        if (snapshot.getApiRequestCount() > 0 && snapshot.getRagNoAnswerRate() > maxRagNoAnswerRate) {
            log.warn("alert_triggered type=RAG_NO_ANSWER_RATE ragNoAnswerRate={} ragNoAnswerCount={}",
                    snapshot.getRagNoAnswerRate(),
                    snapshot.getRagNoAnswerCount());
        }
        if (snapshot.getAgentMaxStepsCount() > maxAgentMaxStepsCount) {
            log.warn("alert_triggered type=AGENT_MAX_STEPS count={}", snapshot.getAgentMaxStepsCount());
        }
        if (snapshot.getTotalTokens() > maxTotalTokens) {
            log.warn("alert_triggered type=TOKEN_USAGE totalTokens={}", snapshot.getTotalTokens());
        }
    }

    public void setMaxLlmErrorRate(double maxLlmErrorRate) {
        this.maxLlmErrorRate = maxLlmErrorRate;
    }

    public void setMaxRagNoAnswerRate(double maxRagNoAnswerRate) {
        this.maxRagNoAnswerRate = maxRagNoAnswerRate;
    }

    public void setMaxAgentMaxStepsCount(long maxAgentMaxStepsCount) {
        this.maxAgentMaxStepsCount = maxAgentMaxStepsCount;
    }

    public void setMaxTotalTokens(long maxTotalTokens) {
        this.maxTotalTokens = maxTotalTokens;
    }
}
