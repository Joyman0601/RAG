package com.yhl.rag.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.tool.QueryOrderToolResult;
import com.yhl.rag.tool.SearchKnowledgeToolResult;
import com.yhl.rag.tool.ToolExecutionContext;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefundWorkflowServiceTest {

    private ToolExecutionService toolExecutionService;

    private AuditLogService auditLogService;

    private RefundWorkflowService refundWorkflowService;

    @BeforeEach
    void setUp() {
        toolExecutionService = mock(ToolExecutionService.class);
        auditLogService = mock(AuditLogService.class);
        refundWorkflowService = new RefundWorkflowService(
                toolExecutionService,
                auditLogService,
                mock(LlmClient.class),
                new LlmProperties(),
                new ObjectMapper()
        );
    }

    @Test
    void startOrContinue_whenOrderIdIsMissing_entersNeedOrderId() {
        RefundWorkflowResponse response = refundWorkflowService.startOrContinue(
                "refund-missing-order",
                "user_001",
                "我想退款"
        );

        assertThat(response.getState()).isEqualTo(RefundWorkflowState.NEED_ORDER_ID);
        assertThat(response.isRequiresConfirmation()).isFalse();
        assertThat(response.getRequestId()).isNotBlank();
    }

    @Test
    void startOrContinue_whenOrderIdIsProvided_transitionsThroughOrderAndPolicyToWaitingConfirmation() {
        mockSuccessfulTools("NOT_SHIPPED");
        refundWorkflowService.startOrContinue("refund-happy-path", "user_001", "我想退款");

        RefundWorkflowResponse response = refundWorkflowService.startOrContinue(
                "refund-happy-path",
                "user_001",
                "订单 ORD002"
        );

        assertThat(response.getState()).isEqualTo(RefundWorkflowState.WAITING_CONFIRMATION);
        assertThat(response.isRequiresConfirmation()).isTrue();
        assertThat(response.getConfirmationId()).isNotBlank();
        assertThat(response.getSteps()).hasSize(4);
        verify(auditLogService).logRefundWorkflowTransition(anyString(), anyString(), eq("user_001"),
                eq(RefundWorkflowState.NEED_ORDER_ID), eq(RefundWorkflowState.ORDER_READY), eq("ORDER_ID_COLLECTED"));
        verify(auditLogService).logRefundWorkflowTransition(anyString(), anyString(), eq("user_001"),
                eq(RefundWorkflowState.ORDER_READY), eq(RefundWorkflowState.ORDER_QUERIED), eq("ORDER_QUERY_SUCCESS"));
        verify(auditLogService).logRefundWorkflowTransition(anyString(), anyString(), eq("user_001"),
                eq(RefundWorkflowState.ORDER_QUERIED), eq(RefundWorkflowState.POLICY_CHECKED), eq("POLICY_SEARCH_FINISHED"));
        verify(auditLogService).logRefundWorkflowTransition(anyString(), anyString(), eq("user_001"),
                eq(RefundWorkflowState.POLICY_CHECKED), eq(RefundWorkflowState.WAITING_CONFIRMATION), eq("POLICY_APPROVED"));
    }

    @Test
    void startOrContinue_whenUserConfirms_transitionsSubmittedAndDone() {
        mockSuccessfulTools("NOT_SHIPPED");
        refundWorkflowService.startOrContinue("refund-confirm", "user_001", "订单 ORD002 退款");

        RefundWorkflowResponse response = refundWorkflowService.startOrContinue(
                "refund-confirm",
                "user_001",
                "确认提交"
        );

        assertThat(response.getState()).isEqualTo(RefundWorkflowState.DONE);
        assertThat(response.isRequiresConfirmation()).isFalse();
        verify(auditLogService).logRefundWorkflowTransition(anyString(), anyString(), eq("user_001"),
                eq(RefundWorkflowState.WAITING_CONFIRMATION), eq(RefundWorkflowState.SUBMITTED), eq("USER_CONFIRMED"));
        verify(auditLogService).logRefundWorkflowTransition(anyString(), anyString(), eq("user_001"),
                eq(RefundWorkflowState.SUBMITTED), eq(RefundWorkflowState.DONE), eq("MOCK_REFUND_CREATED"));
        verify(auditLogService).logRefundSubmitted(anyString(), anyString(), eq("user_001"), anyString(), anyString(), any());
    }

    @Test
    void startOrContinue_whenUserCancels_entersCancelled() {
        mockSuccessfulTools("NOT_SHIPPED");
        refundWorkflowService.startOrContinue("refund-cancel", "user_001", "订单 ORD002 退款");

        RefundWorkflowResponse response = refundWorkflowService.startOrContinue(
                "refund-cancel",
                "user_001",
                "取消"
        );

        assertThat(response.getState()).isEqualTo(RefundWorkflowState.CANCELLED);
        assertThat(response.isRequiresConfirmation()).isFalse();
    }

    @Test
    void startOrContinue_whenOrderIsShipped_entersRejectedByPolicyRule() {
        mockSuccessfulTools("SHIPPED");

        RefundWorkflowResponse response = refundWorkflowService.startOrContinue(
                "refund-rejected",
                "user_001",
                "申请订单 ORD001 退款"
        );

        assertThat(response.getState()).isEqualTo(RefundWorkflowState.REJECTED);
        assertThat(response.isRequiresConfirmation()).isFalse();
        verify(auditLogService).logRefundWorkflowTransition(anyString(), anyString(), eq("user_001"),
                eq(RefundWorkflowState.POLICY_CHECKED), eq(RefundWorkflowState.REJECTED), eq("POLICY_REJECTED_SHIPPED"));
    }

    @Test
    void startOrContinue_whenToolFails_entersFailed() {
        when(toolExecutionService.execute(eq("query_order"), any(), any(ToolExecutionContext.class)))
                .thenReturn(ToolResult.failure("query_order", AgentErrorCode.TOOL_EXECUTION_FAILED.name(), "tool execution failed", 1));

        RefundWorkflowResponse response = refundWorkflowService.startOrContinue(
                "refund-tool-failed",
                "user_001",
                "申请订单 ORD002 退款"
        );

        assertThat(response.getState()).isEqualTo(RefundWorkflowState.FAILED);
        assertThat(response.isRequiresConfirmation()).isFalse();
    }

    private void mockSuccessfulTools(String logisticsStatus) {
        when(toolExecutionService.execute(eq("query_order"), any(), any(ToolExecutionContext.class)))
                .thenReturn(ToolResult.success(
                        "query_order",
                        new QueryOrderToolResult("ORD002", "PAID", new BigDecimal("199.90"), "2026-05-20", logisticsStatus),
                        1
                ));
        when(toolExecutionService.execute(eq("search_knowledge_base"), any(), any(ToolExecutionContext.class)))
                .thenReturn(ToolResult.success(
                        "search_knowledge_base",
                        new SearchKnowledgeToolResult(
                                true,
                                List.of(new SearchKnowledgeToolResult.Context(
                                        "未发货订单允许申请退款；已发货订单不允许自动退款。",
                                        "source-1",
                                        "doc-1",
                                        "refund-policy",
                                        0.9
                                )),
                                List.of(new SearchKnowledgeToolResult.Source("doc-1", "refund-policy", 0)),
                                1
                        ),
                        1
                ));
    }
}
