package com.yhl.rag.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.TestSupport;
import com.yhl.rag.cost.CostGovernanceService;
import com.yhl.rag.cost.CostProperties;
import com.yhl.rag.cost.ModelTier;
import com.yhl.rag.cost.QuotaService;
import com.yhl.rag.cost.RateLimitService;
import com.yhl.rag.cost.TokenEstimator;
import com.yhl.rag.cost.UsageRecordService;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.rag.RagAskService;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.rag.RagSearchOutcome;
import com.yhl.rag.rag.RagSearchResult;
import com.yhl.rag.rag.RagSearchService;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ObservabilityTest {

    @Test
    void requestIdFilter_whenHeaderExists_putsRequestIdIntoMdcAndResponseHeader() throws Exception {
        MetricsService metricsService = new MetricsService();
        RequestIdFilter filter = new RequestIdFilter(new MockCurrentUserProvider(), metricsService, new AlertRuleService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/metrics/llm");
        request.addHeader("X-Request-Id", "req-observe-001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] requestIdInChain = new String[1];

        filter.doFilter(request, response, (FilterChain) (servletRequest, servletResponse) -> {
            requestIdInChain[0] = MDC.get(RequestContext.REQUEST_ID_KEY);
        });

        assertThat(requestIdInChain[0]).isEqualTo("req-observe-001");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-observe-001");
        assertThat(MDC.get(RequestContext.REQUEST_ID_KEY)).isNull();
        assertThat(metricsService.snapshot().getApiRequestCount()).isEqualTo(1);
    }

    @Test
    void ragAsk_whenLoggingContext_doesNotLogFullChunkContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(RagAskService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(new LlmGenerationResult("答案", 10, 2, 12));
        RagSearchService searchService = mock(RagSearchService.class);
        String fullChunk = "完整敏感chunk内容-禁止出现在日志中";
        when(searchService.searchWithMetrics("问题")).thenReturn(new RagSearchOutcome(
                List.of(new RagSearchResult("chunk-1", "doc-1", "policy.md", 0, fullChunk, 0.9)),
                1,
                1
        ));
        try {
            RagProperties ragProps = new RagProperties();
            RagAskService askService = new RagAskService(
                    searchService,
                    llmClient,
                    ragProps,
                    new LlmProperties(),
                    costService(new MetricsService()),
                    new MockCurrentUserProvider(),
                    new MetricsService(),
                    new com.yhl.rag.rag.QueryRewriterService(llmClient, ragProps)
            );

            askService.ask("问题");

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs).contains("rag_context");
            assertThat(logs).doesNotContain(fullChunk);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void usageRecord_whenRecorded_incrementsLlmMetrics() {
        MetricsService metricsService = new MetricsService();
        CostGovernanceService costService = costService(metricsService);

        costService.recordUsage(
                "req-usage",
                "tenant-default",
                "user_001",
                "RAG_ASK",
                ModelTier.STANDARD,
                "prompt",
                List.of(new LlmMessage("user", "你好")),
                new LlmGenerationResult("答案", 5, 3, 8),
                12,
                true
        );

        assertThat(metricsService.snapshot().getLlmCallCount()).isEqualTo(1);
        assertThat(metricsService.snapshot().getTotalTokens()).isEqualTo(8);
    }

    @Test
    void toolExecution_whenToolFails_incrementsToolFailureMetric() {
        ObjectMapper objectMapper = TestSupport.objectMapper();
        ToolRegistry registry = TestSupport.toolRegistry(objectMapper);
        MetricsService metricsService = new MetricsService();
        ToolExecutionService service = new ToolExecutionService(
                registry,
                objectMapper,
                TestSupport.validator(),
                new com.yhl.rag.agent.AgentSafetyPolicy(),
                metricsService
        );

        service.execute("query_order", objectMapper.createObjectNode().put("orderId", "ORD#001"), TestSupport.context());

        assertThat(metricsService.snapshot().getToolFailureCount()).isEqualTo(1);
    }

    @Test
    void alertRule_whenThresholdExceeded_logsWarn() {
        Logger logger = (Logger) LoggerFactory.getLogger(AlertRuleService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AlertRuleService alertRuleService = new AlertRuleService();
            alertRuleService.setMaxLlmErrorRate(0.1);
            alertRuleService.evaluate(new MetricsSnapshot(1, 0, 10, 5, 100, 0, 0, 0));

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("alert_triggered type=LLM_ERROR_RATE");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static CostGovernanceService costService(MetricsService metricsService) {
        CostProperties properties = new CostProperties();
        properties.setRateLimitPerMinute(1_000);
        properties.setUserDailyTokenQuota(100_000);
        properties.setTenantDailyTokenQuota(1_000_000);
        return new CostGovernanceService(
                properties,
                new TokenEstimator(),
                new QuotaService(),
                new RateLimitService(),
                new UsageRecordService(),
                new LlmProperties(),
                metricsService
        );
    }
}
