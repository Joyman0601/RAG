package com.yhl.rag.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.tool.QueryOrderToolResult;
import com.yhl.rag.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConversationStateServiceTest {

    private ConversationStateService conversationStateService;

    @BeforeEach
    void setUp() {
        conversationStateService = new ConversationStateService(new ObjectMapper());
    }

    @Test
    void updateAfterUserMessage_whenMessageHasOrderId_updatesCurrentOrderId() {
        ConversationState state = conversationStateService.getOrCreate("conv-state-1", "user_001");

        boolean updated = conversationStateService.updateAfterUserMessage(state, "帮我查订单 ORD001");

        assertThat(updated).isTrue();
        assertThat(state.getCurrentOrderId()).isEqualTo("ORD001");
    }

    @Test
    void updateAfterToolResult_whenQueryOrderSucceeds_updatesToolSummary() {
        ConversationState state = conversationStateService.getOrCreate("conv-state-2", "user_001");
        ToolResult toolResult = ToolResult.success(
                "query_order",
                new QueryOrderToolResult("ORD001", "PAID", new BigDecimal("199.90"), "2026-05-20", "SHIPPED"),
                3
        );

        boolean updated = conversationStateService.updateAfterToolResult(state, toolResult);

        assertThat(updated).isTrue();
        assertThat(state.getLastToolName()).isEqualTo("query_order");
        assertThat(state.getCurrentOrderId()).isEqualTo("ORD001");
        assertThat(state.getLastToolResultSummary()).contains("orderId=ORD001", "status=PAID", "amount=199.90");
    }

    @Test
    void buildMessages_whenUserUsesEllipsis_injectsCurrentOrderIdIntoContext() {
        ConversationState state = conversationStateService.getOrCreate("conv-state-3", "user_001");
        conversationStateService.updateAfterUserMessage(state, "先查一下 ORD001");
        AgentContextBuilder contextBuilder = new AgentContextBuilder(conversationStateService);

        List<LlmMessage> messages = contextBuilder.buildMessages(
                "user_001",
                "conv-state-3",
                "这个订单可以退款吗",
                List.of("query_order")
        );

        assertThat(messages.get(0).content()).contains("currentOrderId=ORD001");
        assertThat(messages.get(0).content()).contains("use currentOrderId");
    }

    @Test
    void updateAfterToolResult_doesNotStoreSensitiveFieldsInSummary() {
        ConversationState state = conversationStateService.getOrCreate("conv-state-4", "user_001");
        ToolResult toolResult = ToolResult.success(
                "custom_tool",
                Map.of(
                        "orderId", "ORD001",
                        "status", "PAID",
                        "phone", "13800000000",
                        "address", "secret address",
                        "paymentSerialNo", "pay-secret",
                        "internalRemark", "secret note"
                ),
                1
        );

        boolean updated = conversationStateService.updateAfterToolResult(state, toolResult);

        assertThat(updated).isTrue();
        assertThat(state.getLastToolResultSummary()).contains("orderId=ORD001", "status=PAID");
        assertThat(state.getLastToolResultSummary()).doesNotContain("13800000000", "secret address", "pay-secret", "secret note");
    }
}
