package com.yhl.rag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.document.DocumentIngestTask;
import com.yhl.rag.document.DocumentIngestTaskService;
import com.yhl.rag.document.DocumentService;
import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.llm.RerankClient;
import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.vector.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class RagHybridRerankTest {

    private EmbeddingClient embeddingClient;
    private InMemoryVectorStore vectorStore;
    private DocumentService documentService;
    private DocumentIngestTaskService taskService;
    private RagProperties ragProperties;
    private LlmProperties llmProperties;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        vectorStore = new InMemoryVectorStore();
        taskService = new DocumentIngestTaskService();
        KnowledgeBaseVersionService knowledgeBaseVersionService = new KnowledgeBaseVersionService();
        ragProperties = new RagProperties();
        ragProperties.setChunkSize(200);
        ragProperties.setChunkOverlap(20);
        llmProperties = new LlmProperties();
        llmProperties.setEmbeddingModel("test-embedding-model");
        documentService = new DocumentService(
                ragProperties,
                llmProperties,
                embeddingClient,
                new MockCurrentUserProvider(),
                vectorStore,
                taskService,
                knowledgeBaseVersionService
        );
    }

    @Test
    void hybridRerank_appliesRerankOrderToFinalResults() {
        // 两篇文档向量分数相同（mock embedding 一致），唯有 rerank 能决定先后。
        ingestReadyDocument("a.md", "公司差旅报销政策的一般说明文本");
        ingestReadyDocument("b.md", "公司差旅报销政策 WIN 命中关键内容");

        ragProperties.getSearch().setMode(RagProperties.RetrievalMode.HYBRID_RERANK);
        RagSearchService service = serviceWith(new MarkerRerankClient());

        List<RagSearchResult> results = service.searchWithMetrics("报销政策", user("user_001"), false).results();

        assertThat(results).isNotEmpty();
        // 含 WIN 的文档被 rerank 顶到首位，证明精排顺序已落到最终结果。
        assertThat(results.get(0).getContent()).contains("WIN");
        assertThat(results.get(0).getDebugInfo()).isEqualTo("reranked");
    }

    @Test
    void hybridRerank_whenRerankNotConfigured_fallsBackToFusionOrder() {
        ingestReadyDocument("a.md", "报销政策说明");
        ragProperties.getSearch().setMode(RagProperties.RetrievalMode.HYBRID_RERANK);
        RagSearchService service = serviceWith(new RerankClient(llmProperties, new ObjectMapper())); // 未配置 key

        List<RagSearchResult> results = service.searchWithMetrics("报销政策", user("user_001"), false).results();

        assertThat(results).isNotEmpty();
        // 未配置 rerank 时降级为 RRF 融合顺序，不抛错。
        assertThat(results.get(0).getDebugInfo()).startsWith("rrf");
    }

    private RagSearchService serviceWith(RerankClient rerankClient) {
        return new RagSearchService(
                embeddingClient,
                vectorStore,
                ragProperties,
                new MockCurrentUserProvider(),
                documentService,
                new RagSearchCache(),
                new KnowledgeBaseVersionService(),
                llmProperties,
                rerankClient
        );
    }

    private void ingestReadyDocument(String filename, String content) {
        var response = documentService.upload(file(filename, content));
        DocumentIngestTask task = taskService.getByDocumentId(response.getDocumentId());
        documentService.processIngestTask(task);
    }

    private static CurrentUser user(String userId) {
        return new CurrentUser(
                "tenant-default",
                userId,
                "default-department",
                java.util.Set.of("default-department"),
                java.util.Set.of("customer"),
                1
        );
    }

    private static MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile(
                "file",
                filename,
                "text/markdown",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    /** 测试桩：把正文含 "WIN" 的候选排到最前，模拟 Cross-Encoder 精排结果。 */
    private static class MarkerRerankClient extends RerankClient {

        MarkerRerankClient() {
            super(new LlmProperties(), new ObjectMapper());
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<Integer> rerank(String query, List<String> documents, int topN) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                if (documents.get(i).contains("WIN")) {
                    order.add(i);
                }
            }
            for (int i = 0; i < documents.size(); i++) {
                if (!documents.get(i).contains("WIN")) {
                    order.add(i);
                }
            }
            return order.stream().limit(topN <= 0 ? documents.size() : topN).toList();
        }
    }
}
