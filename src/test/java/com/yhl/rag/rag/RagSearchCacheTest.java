package com.yhl.rag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.yhl.rag.document.DocumentInfo;
import com.yhl.rag.document.DocumentIngestTask;
import com.yhl.rag.document.DocumentIngestTaskService;
import com.yhl.rag.document.DocumentService;
import com.yhl.rag.document.DocumentStatus;
import com.yhl.rag.document.DocumentUploadResponse;
import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.vector.InMemoryVectorStore;
import com.yhl.rag.vector.VectorSearchRequest;
import com.yhl.rag.vector.VectorSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class RagSearchCacheTest {

    private EmbeddingClient embeddingClient;

    private CountingVectorStore vectorStore;

    private DocumentService documentService;

    private DocumentIngestTaskService taskService;

    private KnowledgeBaseVersionService knowledgeBaseVersionService;

    private RagSearchCache ragSearchCache;

    private RagSearchService ragSearchService;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        vectorStore = new CountingVectorStore();
        taskService = new DocumentIngestTaskService();
        knowledgeBaseVersionService = new KnowledgeBaseVersionService();
        ragSearchCache = new RagSearchCache();
        RagProperties ragProperties = new RagProperties();
        ragProperties.setChunkSize(200);
        ragProperties.setChunkOverlap(20);
        LlmProperties llmProperties = new LlmProperties();
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
        ragSearchService = new RagSearchService(
                embeddingClient,
                vectorStore,
                ragProperties,
                new MockCurrentUserProvider(),
                documentService,
                ragSearchCache,
                knowledgeBaseVersionService,
                llmProperties
        );
    }

    @Test
    void search_whenQuestionAndPermissionAreSame_reusesRagSearchCache() {
        ingestReadyDocument("policy.md", "报销标准：交通费每天 100 元。");

        ragSearchService.searchWithMetrics("报销标准", user("user_001"), false);
        ragSearchService.searchWithMetrics("报销标准", user("user_001"), false);

        assertThat(vectorStore.searchCount()).isEqualTo(1);
        assertThat(ragSearchCache.size()).isEqualTo(1);
    }

    @Test
    void search_whenUserPermissionSignatureDiffers_doesNotShareRagSearchCache() {
        ingestReadyDocument("policy.md", "报销标准：交通费每天 100 元。");

        ragSearchService.searchWithMetrics("报销标准", user("user_001"), false);
        ragSearchService.searchWithMetrics("报销标准", user("user_002"), false);

        assertThat(vectorStore.searchCount()).isEqualTo(2);
        assertThat(ragSearchCache.size()).isEqualTo(2);
    }

    @Test
    void search_whenKnowledgeBaseVersionChanges_doesNotHitOldRagSearchCache() {
        ingestReadyDocument("policy.md", "报销标准：交通费每天 100 元。");

        ragSearchService.searchWithMetrics("报销标准", user("user_001"), false);
        knowledgeBaseVersionService.incrementAndGet();
        ragSearchService.searchWithMetrics("报销标准", user("user_001"), false);

        assertThat(vectorStore.searchCount()).isEqualTo(2);
    }

    @Test
    void search_whenCacheHitButDocumentNoLongerPermitted_filtersCachedChunkBeforeContext() {
        DocumentUploadResponse response = ingestReadyDocument("policy.md", "报销标准：交通费每天 100 元。");
        List<RagSearchResult> firstResults = ragSearchService.searchWithMetrics("报销标准", user("user_001"), false).results();
        DocumentInfo documentInfo = documentService.getDocumentInfoSnapshot().get(response.getDocumentId());
        documentInfo.setStatus(DocumentStatus.FAILED);

        List<RagSearchResult> secondResults = ragSearchService.searchWithMetrics("报销标准", user("user_001"), false).results();

        assertThat(firstResults).isNotEmpty();
        assertThat(secondResults).isEmpty();
        assertThat(vectorStore.searchCount()).isEqualTo(1);
    }

    private DocumentUploadResponse ingestReadyDocument(String filename, String content) {
        DocumentUploadResponse response = documentService.upload(file(filename, content));
        DocumentIngestTask task = taskService.getByDocumentId(response.getDocumentId());
        assertThat(documentService.processIngestTask(task)).isTrue();
        return response;
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

    private static class CountingVectorStore extends InMemoryVectorStore {

        private final AtomicInteger searchCount = new AtomicInteger();

        @Override
        public List<VectorSearchResult> search(VectorSearchRequest request) {
            searchCount.incrementAndGet();
            return super.search(request);
        }

        int searchCount() {
            return searchCount.get();
        }
    }
}
