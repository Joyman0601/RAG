package com.yhl.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.yhl.rag.chunk.ChunkingService;
import com.yhl.rag.chunk.InMemoryParentStore;
import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.rag.KnowledgeBaseVersionService;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.vector.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class DocumentContextualEmbeddingTest {

    private static final String PREFIX = "定位说明前缀";

    private EmbeddingClient embeddingClient;
    private LlmClient llmClient;
    private InMemoryVectorStore vectorStore;
    private InMemoryParentStore parentStore;
    private DocumentIngestTaskService taskService;
    private RagProperties ragProperties;
    private DocumentService documentService;
    private IngestWorker ingestWorker;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        llmClient = mock(LlmClient.class);
        when(llmClient.generate(anyString(), anyList())).thenReturn(PREFIX);
        vectorStore = new InMemoryVectorStore();
        parentStore = new InMemoryParentStore();
        taskService = new DocumentIngestTaskService();
        ragProperties = new RagProperties();
        documentService = new DocumentService(
                ragProperties,
                new LlmProperties(),
                embeddingClient,
                new MockCurrentUserProvider(),
                vectorStore,
                taskService,
                new KnowledgeBaseVersionService(),
                new ChunkingService(embeddingClient),
                parentStore,
                new ContextualEnricher(llmClient, ragProperties)
        );
        ingestWorker = new IngestWorker(taskService, documentService);
    }

    @Test
    void contextualEnabled_embedsPrefixedText_butStoresOriginalContent() {
        ragProperties.getContextual().setEnabled(true);

        DocumentUploadResponse response = documentService.upload(file("note.txt", "请假需要提前三天申请。"));
        ingestWorker.runOnce();

        // embedding 文本带上下文前缀。
        ArgumentCaptor<String> embedded = ArgumentCaptor.forClass(String.class);
        verify(embeddingClient, atLeastOnce()).embed(embedded.capture());
        assertThat(embedded.getAllValues()).anySatisfy(text ->
                assertThat(text).startsWith(PREFIX).contains("请假需要提前三天申请。"));

        // 展示/回填仍用原文：chunk.content 不含前缀。
        List<DocumentChunk> chunks = documentService.listChunks(response.getDocumentId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.getContent()).doesNotContain(PREFIX));
    }

    @Test
    void contextualDisabled_embedsPlainContent_zeroRegression() {
        ragProperties.getContextual().setEnabled(false);

        documentService.upload(file("note.txt", "请假需要提前三天申请。"));
        ingestWorker.runOnce();

        ArgumentCaptor<String> embedded = ArgumentCaptor.forClass(String.class);
        verify(embeddingClient, atLeastOnce()).embed(embedded.capture());
        assertThat(embedded.getAllValues()).allSatisfy(text ->
                assertThat(text).doesNotContain(PREFIX));
    }

    private MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile(
                "file",
                filename,
                "text/plain",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
