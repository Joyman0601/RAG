package com.yhl.rag.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.TestSupport;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.tool.CancelOrderToolExecutor;
import com.yhl.rag.tool.QueryOrderToolExecutor;
import com.yhl.rag.tool.QueryOrderToolRequest;
import com.yhl.rag.tool.ToolExecutionContext;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

class AgentToolRolloutServiceTest {

    @Test
    void allowedToolNames_whenToolDisabled_doesNotExposeToolToModel() {
        AgentToolRolloutProperties properties = new AgentToolRolloutProperties();
        AgentToolRolloutProperties.ToolPolicy queryPolicy = new AgentToolRolloutProperties.ToolPolicy();
        queryPolicy.setEnabled(false);
        properties.setTools(Map.of("query_order", queryPolicy));

        AllowedToolService allowedToolService = new AllowedToolService(new AgentToolRolloutService(properties));

        assertThat(allowedToolService.allowedToolNames(TestSupport.context()))
                .doesNotContain("query_order")
                .contains("cancel_order", "search_knowledge_base");
    }

    @Test
    void allowedToolNames_whenTenantOrRoleDiffers_returnsDifferentTools() {
        AgentToolRolloutProperties properties = new AgentToolRolloutProperties();
        AgentToolRolloutProperties.ToolPolicy queryPolicy = new AgentToolRolloutProperties.ToolPolicy();
        queryPolicy.setAllowedTenantIds(Set.of("tenant-a"));
        AgentToolRolloutProperties.ToolPolicy knowledgePolicy = new AgentToolRolloutProperties.ToolPolicy();
        knowledgePolicy.setAllowedRoleIds(Set.of("support"));
        properties.setTools(Map.of(
                "query_order", queryPolicy,
                "search_knowledge_base", knowledgePolicy
        ));
        AllowedToolService allowedToolService = new AllowedToolService(new AgentToolRolloutService(properties));

        ToolExecutionContext tenantAUser = context("tenant-a", Set.of("customer"));
        ToolExecutionContext supportUser = context("tenant-b", Set.of("support"));

        assertThat(allowedToolService.allowedToolNames(tenantAUser))
                .contains("query_order")
                .doesNotContain("search_knowledge_base");
        assertThat(allowedToolService.allowedToolNames(supportUser))
                .contains("search_knowledge_base")
                .doesNotContain("query_order");
    }

    @Test
    void run_whenToolIsShadowOnly_validatesButDoesNotExecuteRealExecutor() {
        ObjectMapper objectMapper = TestSupport.objectMapper();
        CountingQueryOrderToolExecutor queryExecutor = new CountingQueryOrderToolExecutor(objectMapper);
        ShadowToolDecisionService shadowDecisionService = new ShadowToolDecisionService(objectMapper);
        AgentToolRolloutProperties properties = new AgentToolRolloutProperties();
        AgentToolRolloutProperties.ToolPolicy policy = new AgentToolRolloutProperties.ToolPolicy();
        policy.setShadowOnly(true);
        properties.setTools(Map.of("query_order", policy));
        AgentLoopService service = loopService(objectMapper, queryExecutor, new AgentToolRolloutService(properties), shadowDecisionService);
        LlmClient llmClient = extractMockLlm(service);
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(TestSupport.llm("""
                        {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD001"}}}
                        """));

        AgentLoopResponse response = service.run("rollout-shadow", "查订单 ORD001", TestSupport.context());

        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.TOOL_SHADOWED.name());
        assertThat(queryExecutor.executedCount()).isZero();
        assertThat(response.getToolDebugInfo()).isNotNull();
        assertThat(response.getToolDebugInfo().isShadowOnly()).isTrue();
        assertThat(shadowDecisionService.recent(10))
                .hasSize(1)
                .first()
                .extracting(ShadowToolDecision::getPolicyDecision, ShadowToolDecision::getValidationResult)
                .containsExactly(ShadowToolPolicyDecision.SHADOW_ONLY, "OK");
    }

    @Test
    void run_whenHighRiskToolRequested_requiresConfirmationByRolloutPolicy() {
        ObjectMapper objectMapper = TestSupport.objectMapper();
        ShadowToolDecisionService shadowDecisionService = new ShadowToolDecisionService(objectMapper);
        AgentLoopService service = loopService(objectMapper, new CountingQueryOrderToolExecutor(objectMapper), new AgentToolRolloutService(new AgentToolRolloutProperties()), shadowDecisionService);
        LlmClient llmClient = extractMockLlm(service);
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(TestSupport.llm("""
                        {"answer":null,"toolCall":{"toolName":"cancel_order","arguments":{"orderId":"ORD001"}}}
                        """));

        AgentLoopResponse response = service.run("rollout-confirm", "取消订单 ORD001", TestSupport.context());

        assertThat(response.isRequiresConfirmation()).isTrue();
        assertThat(response.getConfirmationId()).isNotBlank();
        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.CONFIRMATION_REQUIRED.name());
        assertThat(response.getToolResults()).isEmpty();
        assertThat(response.getToolDebugInfo().isRequiresConfirmation()).isTrue();
    }

    @Test
    void run_whenToolDisabled_refusesEvenIfModelCallsIt() {
        ObjectMapper objectMapper = TestSupport.objectMapper();
        CountingQueryOrderToolExecutor queryExecutor = new CountingQueryOrderToolExecutor(objectMapper);
        ShadowToolDecisionService shadowDecisionService = new ShadowToolDecisionService(objectMapper);
        AgentToolRolloutProperties properties = new AgentToolRolloutProperties();
        AgentToolRolloutProperties.ToolPolicy policy = new AgentToolRolloutProperties.ToolPolicy();
        policy.setEnabled(false);
        properties.setTools(Map.of("query_order", policy));
        AgentLoopService service = loopService(objectMapper, queryExecutor, new AgentToolRolloutService(properties), shadowDecisionService);
        LlmClient llmClient = extractMockLlm(service);
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(TestSupport.llm("""
                        {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD001"}}}
                        """));

        AgentLoopResponse response = service.run("rollout-disabled", "查订单 ORD001", TestSupport.context());

        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.ROLLOUT_BLOCKED.name());
        assertThat(queryExecutor.executedCount()).isZero();
        assertThat(response.getToolDebugInfo().isRolloutBlocked()).isTrue();
        assertThat(shadowDecisionService.recent(10)).hasSize(1);
    }

    @Test
    void run_whenToolExceedsMaxCallsPerRequest_stopsBeforeSecondExecution() {
        ObjectMapper objectMapper = TestSupport.objectMapper();
        CountingQueryOrderToolExecutor queryExecutor = new CountingQueryOrderToolExecutor(objectMapper);
        ShadowToolDecisionService shadowDecisionService = new ShadowToolDecisionService(objectMapper);
        AgentToolRolloutProperties properties = new AgentToolRolloutProperties();
        AgentToolRolloutProperties.ToolPolicy policy = new AgentToolRolloutProperties.ToolPolicy();
        policy.setMaxCallsPerRequest(1);
        properties.setTools(Map.of("query_order", policy));
        AgentLoopService service = loopService(objectMapper, queryExecutor, new AgentToolRolloutService(properties), shadowDecisionService);
        LlmClient llmClient = extractMockLlm(service);
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(
                        TestSupport.llm("""
                                {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD001"}}}
                                """),
                        TestSupport.llm("""
                                {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD002"}}}
                                """)
                );

        AgentLoopResponse response = service.run("rollout-max-calls", "连续查订单", TestSupport.context());

        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.TOOL_MAX_CALLS_EXCEEDED.name());
        assertThat(queryExecutor.executedCount()).isEqualTo(1);
        assertThat(response.getToolResults()).hasSize(1);
        assertThat(response.getToolDebugInfo().isRolloutBlocked()).isTrue();
    }

    private AgentLoopService loopService(
            ObjectMapper objectMapper,
            CountingQueryOrderToolExecutor queryExecutor,
            AgentToolRolloutService rolloutService,
            ShadowToolDecisionService shadowDecisionService
    ) {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                queryExecutor,
                new CancelOrderToolExecutor(objectMapper),
                new TestSupport.FakeSearchKnowledgeToolExecutor(objectMapper)
        ));
        ToolExecutionService toolExecutionService = TestSupport.toolExecutionService(toolRegistry, objectMapper);
        ConversationStateService conversationStateService = new ConversationStateService(objectMapper);
        LlmClient llmClient = mock(LlmClient.class);
        return new AgentLoopService(
                llmClient,
                new LlmProperties(),
                toolRegistry,
                toolExecutionService,
                new AllowedToolService(rolloutService),
                rolloutService,
                shadowDecisionService,
                new ConfirmationService(toolExecutionService, toolRegistry, mock(AuditLogService.class)),
                new AgentLoopConfig(),
                conversationStateService,
                new AgentContextBuilder(conversationStateService),
                objectMapper,
                new com.yhl.rag.cost.CostGovernanceService(
                        new com.yhl.rag.cost.CostProperties(),
                        new com.yhl.rag.cost.TokenEstimator(),
                        new com.yhl.rag.cost.QuotaService(),
                        new com.yhl.rag.cost.RateLimitService(),
                        new com.yhl.rag.cost.UsageRecordService(),
                        new LlmProperties()
                ),
                new com.yhl.rag.observability.MetricsService()
        );
    }

    private LlmClient extractMockLlm(AgentLoopService service) {
        try {
            java.lang.reflect.Field field = AgentLoopService.class.getDeclaredField("llmClient");
            field.setAccessible(true);
            return (LlmClient) field.get(service);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ToolExecutionContext context(String tenantId, Set<String> roles) {
        return new ToolExecutionContext(
                "req-test",
                tenantId,
                "user_001",
                "default-department",
                Set.of("default-department"),
                1,
                roles,
                Set.of("order:query", "order:cancel", "knowledge:search")
        );
    }

    private static class CountingQueryOrderToolExecutor extends QueryOrderToolExecutor {

        private final AtomicInteger executedCount = new AtomicInteger();

        CountingQueryOrderToolExecutor(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public Object execute(QueryOrderToolRequest request, ToolExecutionContext context) {
            executedCount.incrementAndGet();
            return super.execute(request, context);
        }

        int executedCount() {
            return executedCount.get();
        }
    }
}
