package com.yhl.rag.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.TestSupport;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentLoopServiceTest {

    private LlmClient llmClient;

    private AgentLoopService agentLoopService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = TestSupport.objectMapper();
        ToolRegistry toolRegistry = TestSupport.toolRegistry(objectMapper);
        ToolExecutionService toolExecutionService = TestSupport.toolExecutionService(toolRegistry, objectMapper);
        ConversationStateService conversationStateService = new ConversationStateService(objectMapper);
        AgentLoopConfig config = new AgentLoopConfig();
        llmClient = mock(LlmClient.class);

        agentLoopService = new AgentLoopService(
                llmClient,
                new LlmProperties(),
                toolRegistry,
                toolExecutionService,
                new AllowedToolService(),
                new ConfirmationService(toolExecutionService, toolRegistry, mock(AuditLogService.class)),
                config,
                conversationStateService,
                new AgentContextBuilder(conversationStateService),
                objectMapper
        );
    }

    @Test
    void run_whenModelKeepsCallingTools_stopsAtMaxSteps() {
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(
                        TestSupport.llm("""
                                {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD001"}}}
                                """),
                        TestSupport.llm("""
                                {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD002"}}}
                                """),
                        TestSupport.llm("""
                                {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD202605220001"}}}
                                """)
                );

        AgentLoopResponse response = agentLoopService.run("loop-max-steps", "连续查订单", TestSupport.context());

        assertThat(response.isFinished()).isTrue();
        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.AGENT_MAX_STEPS_EXCEEDED.name());
        assertThat(response.getToolResults()).hasSize(3);
    }

    @Test
    void run_whenModelRepeatsSameToolAndArguments_stopsAsDuplicate() {
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(
                        TestSupport.llm("""
                                {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD001"}}}
                                """),
                        TestSupport.llm("""
                                {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD001"}}}
                                """)
                );

        AgentLoopResponse response = agentLoopService.run("loop-duplicate", "重复查订单", TestSupport.context());

        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.DUPLICATE_TOOL_CALL.name());
        assertThat(response.getToolResults()).hasSize(1);
    }

    @Test
    void run_whenToolArgumentsFailValidation_stopsWithValidationError() {
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(TestSupport.llm("""
                        {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD#001"}}}
                        """));

        AgentLoopResponse response = agentLoopService.run("loop-invalid-arguments", "查订单", TestSupport.context());

        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.VALIDATION_ERROR.name());
        assertThat(response.getToolResults()).hasSize(1);
        assertThat(response.getToolResults().get(0).isSuccess()).isFalse();
    }

    @Test
    void run_whenPermissionIsMissing_stopsWithPermissionDenied() {
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(TestSupport.llm("""
                        {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD001"}}}
                        """));

        AgentLoopResponse response = agentLoopService.run(
                "loop-permission",
                "查订单 ORD001",
                TestSupport.contextWithout("order:query")
        );

        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.PERMISSION_DENIED.name());
        assertThat(response.getToolResults()).isEmpty();
    }

    @Test
    void run_whenModelReturnsFinalAnswer_finishesNormally() {
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(TestSupport.llm("""
                        {"answer":"已经完成","toolCall":null}
                        """));

        AgentLoopResponse response = agentLoopService.run("loop-final", "你好", TestSupport.context());

        assertThat(response.isFinished()).isTrue();
        assertThat(response.getStopReason()).isEqualTo("FINAL_ANSWER");
        assertThat(response.getToolResults()).isEmpty();
        assertThat(response.isRequiresConfirmation()).isFalse();
    }

    @Test
    void run_whenHighRiskToolIsRequested_requiresConfirmationAndStops() {
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(TestSupport.llm("""
                        {"answer":null,"toolCall":{"toolName":"cancel_order","arguments":{"orderId":"ORD001"}}}
                        """));

        AgentLoopResponse response = agentLoopService.run("loop-high-risk", "取消订单 ORD001", TestSupport.context());

        assertThat(response.isFinished()).isFalse();
        assertThat(response.isRequiresConfirmation()).isTrue();
        assertThat(response.getConfirmationId()).isNotBlank();
        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.CONFIRMATION_REQUIRED.name());
        assertThat(response.getToolResults()).isEmpty();
    }
}
