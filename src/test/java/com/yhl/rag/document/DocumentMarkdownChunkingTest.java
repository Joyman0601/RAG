package com.yhl.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.yhl.rag.chunk.ChunkStrategy;
import com.yhl.rag.chunk.ChunkingService;
import com.yhl.rag.chunk.InMemoryParentStore;
import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.rag.KnowledgeBaseVersionService;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.vector.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DocumentMarkdownChunkingTest {

    private EmbeddingClient embeddingClient;
    private InMemoryVectorStore vectorStore;
    private InMemoryParentStore parentStore;
    private DocumentIngestTaskService taskService;
    private DocumentService documentService;
    private IngestWorker ingestWorker;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        vectorStore = new InMemoryVectorStore();
        parentStore = new InMemoryParentStore();
        taskService = new DocumentIngestTaskService();
        RagProperties ragProperties = new RagProperties();
        ragProperties.setChunkSize(1000);
        ragProperties.setChunkOverlap(100);
        ragProperties.getChunk().setStrategy(ChunkStrategy.MARKDOWN);
        documentService = new DocumentService(
                ragProperties,
                new LlmProperties(),
                embeddingClient,
                new MockCurrentUserProvider(),
                vectorStore,
                taskService,
                new KnowledgeBaseVersionService(),
                new ChunkingService(embeddingClient),
                parentStore
        );
        ingestWorker = new IngestWorker(taskService, documentService);
    }

    @Test
    void markdownIngest_storesParentsAndChildrenCarryParentId() {
        DocumentUploadResponse response = documentService.upload(file(
                "guide.md",
                "# 安装\n前言段落。\n## 环境要求\n需要 JDK 17。\n"));

        ingestWorker.runOnce();

        List<DocumentChunk> chunks = documentService.listChunks(response.getDocumentId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getParentId()).isNotNull();
            assertThat(parentStore.findById(chunk.getParentId())).isPresent();
        });
        // 面包屑前缀进入子块正文，辅助检索定位。
        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.getContent()).contains("标题：安装 > 环境要求"));
    }

    @Test
    void markdownDelete_removesParentBlocks() {
        DocumentUploadResponse response = documentService.upload(file(
                "guide.md",
                "# 安装\n前言段落。\n"));
        ingestWorker.runOnce();
        String parentId = documentService.listChunks(response.getDocumentId()).get(0).getParentId();
        assertThat(parentStore.findById(parentId)).isPresent();

        documentService.delete(response.getDocumentId());

        assertThat(parentStore.findById(parentId)).isEmpty();
    }

    private MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile(
                "file",
                filename,
                "text/markdown",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
