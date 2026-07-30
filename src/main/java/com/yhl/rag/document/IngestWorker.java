package com.yhl.rag.document;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;

import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IngestWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestWorker.class);
    private final String workerId = "ingest-worker-" + UUID.randomUUID();

    private final DocumentIngestTaskService taskService;

    private final DocumentService documentService;

    public IngestWorker(DocumentIngestTaskService taskService, DocumentService documentService) {
        this.taskService = taskService;
        this.documentService = documentService;
    }

    @Scheduled(fixedDelayString = "${document.ingest.worker-fixed-delay-ms:3000}")
    public void runOnce() {
        Instant now = Instant.now();
        for (DocumentIngestTask task : taskService.findRunnableTasks(now)) {
            if (!taskService.markRunning(task.getTaskId(), workerId, now)) {
                continue;
            }
            try {
                boolean processed = documentService.processIngestTask(task);
                if (!processed) {
                    taskService.markCanceled(task.getTaskId(), "task documentVersion is not current");
                    log.info("document_ingest_canceled taskId={} documentId={} version={}",
                            task.getTaskId(),
                            task.getDocumentId(),
                            task.getDocumentVersion());
                    continue;
                }
                taskService.markSuccess(task.getTaskId());
                log.info("document_ingest_success taskId={} documentId={}", task.getTaskId(), task.getDocumentId());
            } catch (RuntimeException exception) {
                FailureDecision failureDecision = classify(exception);
                taskService.markFailure(
                        task.getTaskId(),
                        task.getCurrentStep(),
                        failureDecision.errorCode(),
                        exception.getMessage(),
                        failureDecision.retryable()
                );
                DocumentIngestTask latestTask = taskService.getByDocumentId(task.getDocumentId());
                if (latestTask != null && latestTask.getStatus() == DocumentIngestTaskStatus.FAILED) {
                    documentService.markDocumentFailed(task.getDocumentId());
                }
                log.warn("document_ingest_failed taskId={} documentId={} step={} retryable={} errorCode={} retryCount={} maxRetry={}",
                        task.getTaskId(),
                        task.getDocumentId(),
                        task.getCurrentStep(),
                        failureDecision.retryable(),
                        failureDecision.errorCode(),
                        task.getRetryCount(),
                        task.getMaxRetry(),
                        exception);
            }
        }
    }

    private static FailureDecision classify(RuntimeException exception) {
        if (exception instanceof DocumentException documentException) {
            return new FailureDecision(documentException.getErrorType(), false);
        }
        if (exception instanceof LlmException llmException) {
            LlmErrorType errorType = llmException.getErrorType();
            boolean retryable = errorType == LlmErrorType.TIMEOUT
                    || errorType == LlmErrorType.HTTP_ERROR
                    || errorType == LlmErrorType.EMPTY_RESPONSE_BODY
                    || errorType == LlmErrorType.EMPTY_OUTPUT
                    || errorType == LlmErrorType.CLIENT_ERROR;
            return new FailureDecision(errorType.name(), retryable);
        }
        if (hasRetryableNetworkCause(exception)) {
            return new FailureDecision("NETWORK_ERROR", true);
        }
        return new FailureDecision("INGEST_EXECUTION_FAILED", true);
    }

    private static boolean hasRetryableNetworkCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record FailureDecision(String errorCode, boolean retryable) {
    }
}
