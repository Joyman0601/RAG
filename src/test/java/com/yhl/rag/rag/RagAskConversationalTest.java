package com.yhl.rag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.yhl.rag.chunk.InMemoryParentStore;
import com.yhl.rag.cost.CostGovernanceService;
import com.yhl.rag.cost.CostProperties;
import com.yhl.rag.cost.QuotaService;
import com.yhl.rag.cost.RateLimitService;
import com.yhl.rag.cost.TokenEstimator;
import com.yhl.rag.cost.UsageRecordService;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.observability.MetricsService;
import com.yhl.rag.security.MockCurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RagAskConversationalTest {

    private LlmClient llmClient;
    private RagSearchService searchService;
    private RagProperties ragProps;
    private ConversationHistoryStore historyStore;
    private RagAskService askService;
    private final String userId = new MockCurrentUserProvider().getCurrentUser().getUserId();

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        searchService = mock(RagSearchService.class);
        ragProps = new RagProperties();
        ragProps.getQueryRewrite().setEnabled(true);
        ragProps.getQueryRewrite().getConversation().setEnabled(true);
        historyStore = new ConversationHistoryStore();

        // LLM 按指令类型分派：对话式改写 / 单轮改写 / 生成答案。
        when(llmClient.generateWithUsage(anyString(), anyList())).thenAnswer(invocation -> {
            String instructions = invocation.getArgument(0);
            if (instructions != null && instructions.contains("对话式")) {
                return new LlmGenerationResult("ThinkPad X1 的内存大小", 20, 6, 26);
            }
            if (instructions != null && instructions.contains("口语化")) {
                // 单轮改写：原样回显问题，便于断言检索 query。
                return new LlmGenerationResult("ThinkPad X1 的价格", 10, 4, 14);
            }
            return new LlmGenerationResult("回答内容", 10, 2, 12);
        });
        when(searchService.searchWithMetrics(anyString())).thenAnswer(invocation -> new RagSearchOutcome(
                List.of(new RagSearchResult("c1", "doc-1", "guide.md", 0, "命中正文", 0.9)),
                1,
                1
        ));

        askService = new RagAskService(
                searchService,
                llmClient,
                ragProps,
                new LlmProperties(),
                costService(),
                new MockCurrentUserProvider(),
                new MetricsService(),
                new QueryRewriterService(llmClient, ragProps),
                new InMemoryParentStore(),
                historyStore
        );
    }

    @Test
    void multiTurn_secondTurnUsesCoreferenceResolvedQueryAndRecordsHistory() {
        askService.ask("ThinkPad X1 的价格", "conv-1", false);
        // 第一轮后历史记录了 1 轮。
        assertThat(historyStore.get(userId, "conv-1").recentTurns()).hasSize(1);

        askService.ask("它的内存呢", "conv-1", false);

        // 第二轮检索用的是指代消解后的自包含 query。
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchService, org.mockito.Mockito.atLeastOnce()).searchWithMetrics(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues()).contains("ThinkPad X1 的内存大小");
        // 两轮都记进历史。
        assertThat(historyStore.get(userId, "conv-1").recentTurns()).hasSize(2);
    }

    @Test
    void noConversationId_usesSingleTurnAndDoesNotRecord() {
        askService.ask("ThinkPad X1 的价格", null, false);

        // 无 conversationId：历史不被写入（零回归路径）。
        assertThat(historyStore.get(userId, "conv-1").isEmpty()).isTrue();
        // 检索 query 为单轮改写结果（未做指代消解）。
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchService).searchWithMetrics(queryCaptor.capture());
        assertThat(queryCaptor.getValue()).isEqualTo("ThinkPad X1 的价格");
    }

    @Test
    void conversationDisabled_doesNotRecordEvenWithConversationId() {
        ragProps.getQueryRewrite().getConversation().setEnabled(false);

        askService.ask("ThinkPad X1 的价格", "conv-1", false);

        assertThat(historyStore.get(userId, "conv-1").isEmpty()).isTrue();
    }

    private static CostGovernanceService costService() {
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
                new MetricsService()
        );
    }
}
