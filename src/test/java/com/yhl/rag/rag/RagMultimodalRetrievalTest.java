package com.yhl.rag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.yhl.rag.chunk.ChunkingService;
import com.yhl.rag.chunk.InMemoryParentStore;
import com.yhl.rag.document.DocumentService;
import com.yhl.rag.document.DocumentUploadResponse;
import com.yhl.rag.document.DocumentIngestTaskService;
import com.yhl.rag.document.IngestWorker;
import com.yhl.rag.document.Modality;
import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.vector.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 真多模态向量空间端到端：图文混排小语料下，纯文本 query 直接召回图像 chunk
 * （图片走 VL 图像 embedding，文本走文本 embedding，二者同空间），而非图转文。
 */
class RagMultimodalRetrievalTest {

    // 同空间里：图像向量 [0,1]，无关文本向量 [1,0]；query "组织架构图" 投到 [0,1] 故命中图像。
    private static final List<Double> IMAGE_VECTOR = List.of(0.0, 1.0);
    private static final List<Double> TEXT_VECTOR = List.of(1.0, 0.0);
    private static final String IMAGE_QUERY = "组织架构图";

    private EmbeddingClient embeddingClient;
    private InMemoryVectorStore vectorStore;
    private RagProperties ragProperties;
    private MockCurrentUserProvider currentUserProvider;
    private DocumentService documentService;
    private IngestWorker ingestWorker;
    private RagSearchService searchService;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(TEXT_VECTOR);
        when(embeddingClient.embed(IMAGE_QUERY)).thenReturn(IMAGE_VECTOR);
        when(embeddingClient.embedImage(any(), anyString())).thenReturn(IMAGE_VECTOR);

        vectorStore = new InMemoryVectorStore();
        ragProperties = new RagProperties();
        ragProperties.getMultimodal().setEnabled(true);
        currentUserProvider = new MockCurrentUserProvider();
        DocumentIngestTaskService taskService = new DocumentIngestTaskService();
        documentService = new DocumentService(
                ragProperties,
                new LlmProperties(),
                embeddingClient,
                currentUserProvider,
                vectorStore,
                taskService,
                new com.yhl.rag.rag.KnowledgeBaseVersionService(),
                new ChunkingService(embeddingClient),
                new InMemoryParentStore()
        );
        ingestWorker = new IngestWorker(taskService, documentService);
        searchService = new RagSearchService(embeddingClient, vectorStore, ragProperties, currentUserProvider);
    }

    @Test
    void textQuery_recallsImageChunk_inSameVectorSpace() {
        // 语料：一篇无关文本 + 一张图片。
        documentService.upload(new MockMultipartFile("file", "policy.txt", "text/plain",
                "报销需要部门经理审批。".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        DocumentUploadResponse imageDoc = documentService.upload(
                new MockMultipartFile("file", "org-chart.png", "image/png", new byte[]{1, 2, 3, 4}));
        ingestWorker.runOnce();

        List<RagSearchResult> results = searchService.search(IMAGE_QUERY);

        assertThat(results).isNotEmpty();
        RagSearchResult top = results.get(0);
        assertThat(top.getModality()).isEqualTo(Modality.IMAGE.name());
        assertThat(top.getImageRef()).isNotBlank();
        assertThat(top.getDocumentId()).isEqualTo(imageDoc.getDocumentId());
        assertThat(documentService.getImage(top.getImageRef())).isPresent();
    }
}
