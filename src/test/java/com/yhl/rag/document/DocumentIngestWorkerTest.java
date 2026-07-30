package com.yhl.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.rag.RagSearchService;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.vector.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DocumentIngestWorkerTest {

    private EmbeddingClient embeddingClient;

    private InMemoryVectorStore vectorStore;

    private DocumentIngestTaskService taskService;

    private DocumentService documentService;

    private IngestWorker ingestWorker;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        vectorStore = new InMemoryVectorStore();
        taskService = new DocumentIngestTaskService();
        RagProperties ragProperties = new RagProperties();
        ragProperties.setChunkSize(200);
        ragProperties.setChunkOverlap(20);
        documentService = new DocumentService(
                ragProperties,
                new LlmProperties(),
                embeddingClient,
                new MockCurrentUserProvider(),
                vectorStore,
                taskService
        );
        ingestWorker = new IngestWorker(taskService, documentService);
    }

    @Test
    void upload_whenCalled_returnsDocumentIdAndTaskIdWithoutVectorSave() {
        DocumentUploadResponse response = documentService.upload(file("policy.md", "报销标准：交通费每天 100 元。"));

        assertThat(response.getDocumentId()).isNotBlank();
        assertThat(response.getTaskId()).isNotBlank();
        assertThat(documentService.listDocuments()).singleElement()
                .extracting(DocumentInfo::getStatus)
                .isEqualTo(DocumentStatus.UPLOADED);
        assertThat(taskService.getByDocumentId(response.getDocumentId()).getStatus()).isEqualTo(DocumentIngestTaskStatus.PENDING);
        assertThat(vectorStore.getEmbeddingSnapshot()).isEmpty();
    }

    @Test
    void runOnce_whenTaskSucceeds_marksDocumentReadyAndTaskSuccess() {
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        DocumentUploadResponse response = documentService.upload(file("policy.md", "报销标准：交通费每天 100 元。"));

        ingestWorker.runOnce();

        DocumentIngestTask task = taskService.getByDocumentId(response.getDocumentId());
        assertThat(task.getStatus()).isEqualTo(DocumentIngestTaskStatus.SUCCESS);
        assertThat(task.getCurrentStep()).isEqualTo(DocumentIngestStep.DONE);
        assertThat(documentService.listDocuments()).singleElement()
                .extracting(DocumentInfo::getStatus)
                .isEqualTo(DocumentStatus.READY);
        assertThat(documentService.listChunks(response.getDocumentId())).isNotEmpty();
        assertThat(vectorStore.getEmbeddingSnapshot()).isNotEmpty();
    }

    @Test
    void runOnce_whenRetryableEmbeddingFails_entersRetrying() {
        when(embeddingClient.embed(anyString()))
                .thenThrow(new LlmException(LlmErrorType.TIMEOUT, "embedding timeout"));
        DocumentUploadResponse response = documentService.upload(file("policy.md", "报销标准：交通费每天 100 元。"));

        ingestWorker.runOnce();

        DocumentIngestTask firstAttempt = taskService.getByDocumentId(response.getDocumentId());
        assertThat(firstAttempt.getStatus()).isEqualTo(DocumentIngestTaskStatus.RETRYING);
        assertThat(firstAttempt.getRetryCount()).isEqualTo(1);
        assertThat(firstAttempt.getCurrentStep()).isEqualTo(DocumentIngestStep.EMBEDDING);
        assertThat(firstAttempt.getErrorCode()).isEqualTo(LlmErrorType.TIMEOUT.name());
        assertThat(firstAttempt.getErrorMessage()).contains("embedding timeout");
        assertThat(firstAttempt.getNextRetryAt()).isAfter(Instant.now());
    }

    @Test
    void runOnce_whenRetryableFailureReachesMaxRetry_marksTaskAndDocumentFailed() {
        when(embeddingClient.embed(anyString()))
                .thenThrow(new LlmException(LlmErrorType.TIMEOUT, "embedding timeout"));
        DocumentUploadResponse response = documentService.upload(file("policy.md", "报销标准：交通费每天 100 元。"));
        DocumentIngestTask task = taskService.getByDocumentId(response.getDocumentId());
        task.setMaxRetry(2);

        ingestWorker.runOnce();
        task.setNextRetryAt(Instant.now().minusSeconds(1));
        ingestWorker.runOnce();

        DocumentIngestTask failedTask = taskService.getByDocumentId(response.getDocumentId());
        assertThat(failedTask.getStatus()).isEqualTo(DocumentIngestTaskStatus.FAILED);
        assertThat(failedTask.getRetryCount()).isEqualTo(2);
        assertThat(documentService.listDocuments()).singleElement()
                .extracting(DocumentInfo::getStatus)
                .isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void runOnce_whenNonRetryableFailureOccurs_marksFailedWithoutRetry() {
        documentService = documentServiceWithChunkSize(0);
        ingestWorker = new IngestWorker(taskService, documentService);
        DocumentUploadResponse response = documentService.upload(file("policy.md", "报销标准：交通费每天 100 元。"));

        DocumentIngestTask failedTask = taskService.getByDocumentId(response.getDocumentId());
        ingestWorker.runOnce();

        assertThat(failedTask.getStatus()).isEqualTo(DocumentIngestTaskStatus.FAILED);
        assertThat(failedTask.getRetryCount()).isZero();
        assertThat(failedTask.getErrorCode()).isEqualTo("DOCUMENT_INVALID_CHUNK_CONFIG");
        assertThat(documentService.listDocuments()).singleElement()
                .extracting(DocumentInfo::getStatus)
                .isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void processIngestTask_whenRepeated_doesNotCreateDuplicateChunksOrVectors() {
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        DocumentUploadResponse response = documentService.upload(file("policy.md", "报销标准：交通费每天 100 元。"));

        ingestWorker.runOnce();
        DocumentIngestTask task = taskService.getByDocumentId(response.getDocumentId());
        documentService.processIngestTask(task);

        assertThat(documentService.listChunks(response.getDocumentId())).hasSize(1);
        assertThat(vectorStore.getEmbeddingSnapshot()).hasSize(1);
    }

    @Test
    void runOnce_whenTaskVersionIsStale_cancelsTaskAndDoesNotOverrideCurrentReadyDocument() {
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        DocumentUploadResponse response = documentService.upload(file("policy.md", "旧版报销标准。"));
        documentService.update(response.getDocumentId(), file("policy.md", "新版报销标准。"));

        ingestWorker.runOnce();

        DocumentIngestTask staleTask = taskService.getByDocumentId(response.getDocumentId());
        assertThat(staleTask.getStatus()).isEqualTo(DocumentIngestTaskStatus.CANCELED);
        DocumentInfo documentInfo = documentService.listDocuments().get(0);
        assertThat(documentInfo.getVersion()).isEqualTo(2);
        assertThat(documentInfo.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(documentService.getDocumentText(response.getDocumentId())).isEqualTo("新版报销标准。");
    }

    @Test
    void findRunnableTasks_whenRunningLockTimesOut_reschedulesTask() {
        DocumentUploadResponse response = documentService.upload(file("policy.md", "报销标准：交通费每天 100 元。"));
        DocumentIngestTask task = taskService.getByDocumentId(response.getDocumentId());
        Instant now = Instant.now();
        task.setNextRetryAt(now.minusSeconds(601));
        assertThat(taskService.markRunning(task.getTaskId(), "worker-a", now.minusSeconds(600))).isTrue();

        List<DocumentIngestTask> runnableTasks = taskService.findRunnableTasks(now);

        assertThat(runnableTasks).extracting(DocumentIngestTask::getTaskId).contains(task.getTaskId());
        assertThat(task.getStatus()).isEqualTo(DocumentIngestTaskStatus.RETRYING);
        assertThat(task.getLockedBy()).isNull();
        assertThat(task.getErrorCode()).isEqualTo("INGEST_LOCK_TIMEOUT");
    }

    @Test
    void runOnce_whenGenericEmbeddingFails_recordsErrorAndEventuallyMarksDocumentFailed() {
        when(embeddingClient.embed(anyString())).thenThrow(new RuntimeException("embedding unavailable"));
        DocumentUploadResponse response = documentService.upload(file("policy.md", "报销标准：交通费每天 100 元。"));
        DocumentIngestTask task = taskService.getByDocumentId(response.getDocumentId());

        ingestWorker.runOnce();
        task.setNextRetryAt(Instant.now().minusSeconds(1));
        ingestWorker.runOnce();
        task.setNextRetryAt(Instant.now().minusSeconds(1));
        ingestWorker.runOnce();

        DocumentIngestTask failedTask = taskService.getByDocumentId(response.getDocumentId());
        assertThat(failedTask.getStatus()).isEqualTo(DocumentIngestTaskStatus.FAILED);
        assertThat(documentService.listDocuments()).singleElement()
                .extracting(DocumentInfo::getStatus)
                .isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void ragSearch_whenDocumentIsUploadedButNotReady_doesNotRecallIt() {
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        documentService.upload(file("policy.md", "报销标准：交通费每天 100 元。"));
        RagSearchService ragSearchService = new RagSearchService(
                embeddingClient,
                vectorStore,
                new RagProperties(),
                new MockCurrentUserProvider()
        );

        assertThat(ragSearchService.search("报销标准")).isEmpty();
    }

    private MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile(
                "file",
                filename,
                "text/markdown",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private DocumentService documentServiceWithChunkSize(int chunkSize) {
        RagProperties ragProperties = new RagProperties();
        ragProperties.setChunkSize(chunkSize);
        ragProperties.setChunkOverlap(0);
        return new DocumentService(
                ragProperties,
                new LlmProperties(),
                embeddingClient,
                new MockCurrentUserProvider(),
                vectorStore,
                taskService
        );
    }
}
