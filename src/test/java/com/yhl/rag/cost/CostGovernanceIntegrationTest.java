package com.yhl.rag.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.TestSupport;
import com.yhl.rag.agent.AgentContextBuilder;
import com.yhl.rag.agent.AgentErrorCode;
import com.yhl.rag.agent.AgentLoopConfig;
import com.yhl.rag.agent.AgentLoopResponse;
import com.yhl.rag.agent.AgentLoopService;
import com.yhl.rag.agent.AllowedToolService;
import com.yhl.rag.agent.AuditLogService;
import com.yhl.rag.agent.ConfirmationService;
import com.yhl.rag.agent.ConversationStateService;
import com.yhl.rag.chat.ChatService;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.rag.RagAskResponse;
import com.yhl.rag.rag.RagAskService;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.rag.RagSearchOutcome;
import com.yhl.rag.rag.RagSearchResult;
import com.yhl.rag.rag.RagSearchService;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CostGovernanceIntegrationTest {

    @Test
    void chat_whenInputTokensExceedBudget_rejectsBeforeCallingLlm() {
        LlmClient llmClient = mock(LlmClient.class);
        CostProperties properties = properties();
        properties.setChatMaxInputTokens(3);
        properties.setUserDailyTokenQuota(10_000);
        CostGovernanceService costService = costService(properties, new LlmProperties());
        ChatService chatService = new ChatService(
                llmClient,
                costService,
                new MockCurrentUserProvider(),
                new LlmProperties()
        );

        assertThatThrownBy(() -> chatService.chat("cost-chat", "这是一个很长很长的输入"))
                .isInstanceOf(CostException.class)
                .extracting("errorCode")
                .isEqualTo(CostErrorCode.TOKEN_BUDGET_EXCEEDED);
        verifyNoInteractions(llmClient);
    }

    @Test
    void quota_whenUserDailyQuotaIsExceeded_rejectsRequest() {
        LlmClient llmClient = mock(LlmClient.class);
        CostProperties properties = properties();
        properties.setUserDailyTokenQuota(5);
        CostGovernanceService costService = costService(properties, new LlmProperties());
        ChatService chatService = new ChatService(
                llmClient,
                costService,
                new MockCurrentUserProvider(),
                new LlmProperties()
        );

        assertThatThrownBy(() -> chatService.chat("cost-quota", "你好"))
                .isInstanceOf(CostException.class)
                .extracting("errorCode")
                .isEqualTo(CostErrorCode.QUOTA_EXCEEDED);
        verifyNoInteractions(llmClient);
    }

    @Test
    void ragAsk_whenContextTokenBudgetIsSmall_truncatesLowerScoreChunks() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(new LlmGenerationResult("答案", null, null, null));
        RagSearchService searchService = mock(RagSearchService.class);
        when(searchService.searchWithMetrics("报销标准")).thenReturn(new RagSearchOutcome(
                List.of(
                        new RagSearchResult("chunk-high", "doc-1", "policy.md", 0, "第一段", 0.95),
                        new RagSearchResult("chunk-low", "doc-1", "policy.md", 1, "第二段".repeat(200), 0.3)
                ),
                1,
                1
        ));
        CostProperties properties = properties();
        properties.setRagMaxContextTokens(20);
        RagAskService askService = new RagAskService(
                searchService,
                llmClient,
                new RagProperties(),
                new LlmProperties(),
                costService(properties, new LlmProperties()),
                new MockCurrentUserProvider()
        );

        RagAskResponse response = askService.ask("报销标准");

        ArgumentCaptor<List<LlmMessage>> messages = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateWithUsage(anyString(), messages.capture());
        String prompt = messages.getValue().get(0).content();
        assertThat(response.getSources()).extracting("chunkId").containsExactly("chunk-high");
        assertThat(prompt).contains("第一段");
        assertThat(prompt).doesNotContain("第二段");
    }

    @Test
    void agentLoop_whenMaxLlmCallsExceeded_stopsEvenIfMaxStepsRemain() {
        ObjectMapper objectMapper = TestSupport.objectMapper();
        ToolRegistry toolRegistry = TestSupport.toolRegistry(objectMapper);
        ToolExecutionService toolExecutionService = TestSupport.toolExecutionService(toolRegistry, objectMapper);
        ConversationStateService conversationStateService = new ConversationStateService(objectMapper);
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(TestSupport.llm("""
                        {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"ORD001"}}}
                        """));
        CostProperties properties = properties();
        properties.setAgentMaxLlmCalls(1);
        properties.setAgentMaxSteps(3);
        AgentLoopConfig config = new AgentLoopConfig();
        config.setMaxSteps(3);
        AgentLoopService service = new AgentLoopService(
                llmClient,
                new LlmProperties(),
                toolRegistry,
                toolExecutionService,
                new AllowedToolService(),
                new ConfirmationService(toolExecutionService, toolRegistry, mock(AuditLogService.class)),
                config,
                conversationStateService,
                new AgentContextBuilder(conversationStateService),
                objectMapper,
                costService(properties, new LlmProperties())
        );

        AgentLoopResponse response = service.run("cost-agent-loop", "连续查订单", TestSupport.context());

        assertThat(response.getStopReason()).isEqualTo(AgentErrorCode.AGENT_MAX_LLM_CALLS_EXCEEDED.name());
        assertThat(response.getToolResults()).hasSize(1);
    }

    @Test
    void usageRecord_whenDifferentModelTiersAreRecorded_keepsTierAndTokenUsage() {
        UsageRecordService usageRecordService = new UsageRecordService();
        CostGovernanceService service = new CostGovernanceService(
                properties(),
                new TokenEstimator(),
                new QuotaService(),
                new RateLimitService(),
                usageRecordService,
                new LlmProperties()
        );
        List<LlmMessage> messages = List.of(new LlmMessage("user", "你好"));

        service.recordUsage("req-1", "tenant-default", "user_001", "INTENT", ModelTier.FAST, "prompt", messages, new LlmGenerationResult("ok", 2, 1, 3), 10, true);
        service.recordUsage("req-2", "tenant-default", "user_001", "RAG", ModelTier.STANDARD, "prompt", messages, new LlmGenerationResult("ok", 4, 2, 6), 20, true);

        assertThat(usageRecordService.list()).hasSize(2);
        assertThat(usageRecordService.list()).extracting(UsageRecord::getModel)
                .containsExactly("FAST:default", "STANDARD:default");
        assertThat(usageRecordService.list()).extracting(UsageRecord::getTotalTokens)
                .containsExactly(3, 6);
    }

    private static CostProperties properties() {
        CostProperties properties = new CostProperties();
        properties.setChatMaxInputTokens(10_000);
        properties.setRagMaxContextTokens(1_000);
        properties.setRagMaxOutputTokens(200);
        properties.setAgentMaxSteps(3);
        properties.setAgentMaxLlmCalls(4);
        properties.setUserDailyTokenQuota(100_000);
        properties.setTenantDailyTokenQuota(1_000_000);
        properties.setRateLimitPerMinute(1_000);
        return properties;
    }

    private static CostGovernanceService costService(CostProperties properties, LlmProperties llmProperties) {
        return new CostGovernanceService(
                properties,
                new TokenEstimator(),
                new QuotaService(),
                new RateLimitService(),
                new UsageRecordService(),
                llmProperties
        );
    }
}
