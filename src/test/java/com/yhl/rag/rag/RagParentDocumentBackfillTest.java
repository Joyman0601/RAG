package com.yhl.rag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import com.yhl.rag.chunk.InMemoryParentStore;
import com.yhl.rag.chunk.ParentBlock;
import com.yhl.rag.cost.CostGovernanceService;
import com.yhl.rag.cost.CostProperties;
import com.yhl.rag.cost.QuotaService;
import com.yhl.rag.cost.RateLimitService;
import com.yhl.rag.cost.TokenEstimator;
import com.yhl.rag.cost.UsageRecordService;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.observability.MetricsService;
import com.yhl.rag.security.MockCurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RagParentDocumentBackfillTest {

    @Test
    void ask_whenParentDocumentEnabled_backfillsParentContentAndDedupsByParentId() {
        InMemoryParentStore parentStore = new InMemoryParentStore();
        parentStore.saveAll(List.of(parent("p1", "父块完整正文：安装步骤总览")));
        RagProperties ragProps = new RagProperties();
        ragProps.getChunk().getParentDocument().setEnabled(true);

        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(new LlmGenerationResult("答案", 10, 2, 12));
        RagSearchService searchService = mock(RagSearchService.class);
        when(searchService.searchWithMetrics("问题")).thenReturn(new RagSearchOutcome(
                List.of(
                        resultWithParent("c1", "子块A正文", "p1", 0.9),
                        resultWithParent("c2", "子块B正文", "p1", 0.8),
                        resultWithParent("c3", "独立子块C", null, 0.7)
                ),
                1,
                1
        ));

        RagAskService askService = askService(searchService, llmClient, ragProps, parentStore);
        RagAskResponse response = askService.ask("问题");

        String prompt = capturePrompt(llmClient);
        // 父块正文回填且去重：p1 只出现一次，子块 A/B 自身正文被父块取代。
        assertThat(prompt).contains("父块完整正文：安装步骤总览");
        assertThat(prompt).doesNotContain("子块A正文");
        assertThat(prompt).doesNotContain("子块B正文");
        // 无父块的子块走原文。
        assertThat(prompt).contains("独立子块C");
        // sources 仍指向命中的子块（c1 代表父块组，c2 被去重剔除）。
        assertThat(response.getSources()).extracting(RagSource::getChunkId).containsExactly("c1", "c3");
    }

    @Test
    void ask_whenParentDocumentDisabled_usesChildContent() {
        InMemoryParentStore parentStore = new InMemoryParentStore();
        parentStore.saveAll(List.of(parent("p1", "父块完整正文")));
        RagProperties ragProps = new RagProperties();

        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generateWithUsage(anyString(), anyList()))
                .thenReturn(new LlmGenerationResult("答案", 10, 2, 12));
        RagSearchService searchService = mock(RagSearchService.class);
        when(searchService.searchWithMetrics("问题")).thenReturn(new RagSearchOutcome(
                List.of(resultWithParent("c1", "子块A正文", "p1", 0.9)),
                1,
                1
        ));

        RagAskService askService = askService(searchService, llmClient, ragProps, parentStore);
        askService.ask("问题");

        String prompt = capturePrompt(llmClient);
        assertThat(prompt).contains("子块A正文");
        assertThat(prompt).doesNotContain("父块完整正文");
    }

    private static RagSearchResult resultWithParent(String chunkId, String content, String parentId, double score) {
        RagSearchResult result = new RagSearchResult(chunkId, "doc-1", "guide.md", 0, content, score);
        result.setParentId(parentId);
        return result;
    }

    private static String capturePrompt(LlmClient llmClient) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(llmClient).generateWithUsage(anyString(), messages.capture());
        return messages.getValue().get(0).content();
    }

    private static ParentBlock parent(String parentId, String content) {
        return new ParentBlock(parentId, "doc-1", content, 1, "tenant-default", "owner-1", "dept-1",
                com.yhl.rag.document.DocumentVisibility.DEPARTMENT, Set.of(), Set.of(), 0);
    }

    private static RagAskService askService(RagSearchService searchService, LlmClient llmClient,
                                            RagProperties ragProps, InMemoryParentStore parentStore) {
        return new RagAskService(
                searchService,
                llmClient,
                ragProps,
                new LlmProperties(),
                costService(),
                new MockCurrentUserProvider(),
                new MetricsService(),
                new QueryRewriterService(llmClient, ragProps),
                parentStore
        );
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
