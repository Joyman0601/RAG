package com.yhl.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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

class DocumentMultimodalIngestTest {

    private EmbeddingClient embeddingClient;
    private InMemoryVectorStore vectorStore;
    private RagProperties ragProperties;
    private DocumentIngestTaskService taskService;
    private DocumentService documentService;
    private IngestWorker ingestWorker;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        when(embeddingClient.embedImage(any(), anyString())).thenReturn(List.of(0.0, 1.0));
        vectorStore = new InMemoryVectorStore();
        ragProperties = new RagProperties();
        ragProperties.getMultimodal().setEnabled(true);
        taskService = new DocumentIngestTaskService();
        documentService = new DocumentService(
                ragProperties,
                new LlmProperties(),
                embeddingClient,
                new MockCurrentUserProvider(),
                vectorStore,
                taskService,
                new KnowledgeBaseVersionService(),
                new ChunkingService(embeddingClient),
                new InMemoryParentStore()
        );
        ingestWorker = new IngestWorker(taskService, documentService);
    }

    @Test
    void imageUpload_createsImageChunk_embeddedViaVlEndpoint() {
        DocumentUploadResponse response = documentService.upload(
                new MockMultipartFile("file", "org-chart.png", "image/png", new byte[]{1, 2, 3, 4}));

        ingestWorker.runOnce();

        // 图像走 embedImage（VL 端点），不走文本 embed。
        verify(embeddingClient).embedImage(any(), anyString());
        verify(embeddingClient, never()).embed(anyString());

        List<DocumentChunk> chunks = documentService.listChunks(response.getDocumentId());
        assertThat(chunks).hasSize(1);
        DocumentChunk imageChunk = chunks.get(0);
        assertThat(imageChunk.getModality()).isEqualTo(Modality.IMAGE);
        assertThat(imageChunk.getImageRef()).isNotBlank();
        assertThat(documentService.getImage(imageChunk.getImageRef())).isPresent();
        // 向量来自 VL 图像 embedding，与文本进同一空间。
        assertThat(documentService.getChunkEmbedding(imageChunk.getChunkId())).containsExactly(0.0, 1.0);
    }

    @Test
    void textUpload_zeroRegression_usesTextEmbeddingOnly() {
        DocumentUploadResponse response = documentService.upload(
                new MockMultipartFile("file", "note.txt", "text/plain",
                        "请假需要提前三天申请。".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ingestWorker.runOnce();

        verify(embeddingClient, never()).embedImage(any(), anyString());
        List<DocumentChunk> chunks = documentService.listChunks(response.getDocumentId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getModality()).isEqualTo(Modality.TEXT);
            assertThat(chunk.getImageRef()).isNull();
        });
    }

    @Test
    void imageUpload_rejectedWhenMultimodalDisabled() {
        ragProperties.getMultimodal().setEnabled(false);

        assertThatThrownBy(() -> documentService.upload(
                new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3})))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("仅支持上传");
    }

    @Test
    void imageUpload_acceptedWhenMultimodalEnabled() {
        DocumentUploadResponse response = documentService.upload(
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{5, 6, 7}));

        assertThat(response.getDocumentId()).isNotBlank();
    }

    @Test
    void deleteImageDocument_releasesStoredImage() {
        DocumentUploadResponse response = documentService.upload(
                new MockMultipartFile("file", "diagram.png", "image/png", new byte[]{9, 9, 9}));
        ingestWorker.runOnce();
        String imageRef = documentService.listChunks(response.getDocumentId()).get(0).getImageRef();
        assertThat(documentService.getImage(imageRef)).isPresent();

        documentService.delete(response.getDocumentId());

        assertThat(documentService.getImage(imageRef)).isEmpty();
    }
}
