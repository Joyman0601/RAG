package com.yhl.rag.document;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DocumentIngestTaskService {

    private static final int DEFAULT_MAX_RETRY = 3;
    private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofMinutes(5);

    private final ConcurrentMap<String, DocumentIngestTask> tasks = new ConcurrentHashMap<>();

    public DocumentIngestTask create(String documentId) {
        return create(documentId, 1);
    }

    public DocumentIngestTask create(String documentId, int documentVersion) {
        DocumentIngestTask task = new DocumentIngestTask(
                UUID.randomUUID().toString(),
                documentId,
                documentVersion,
                DEFAULT_MAX_RETRY
        );
        tasks.put(task.getTaskId(), task);
        return task;
    }

    public DocumentIngestTask getByDocumentId(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return null;
        }
        return tasks.values().stream()
                .filter(task -> documentId.equals(task.getDocumentId()))
                .max(Comparator.comparing(DocumentIngestTask::getCreatedAt))
                .orElse(null);
    }

    public List<DocumentIngestTask> findRunnableTasks() {
        return findRunnableTasks(Instant.now());
    }

    public List<DocumentIngestTask> findRunnableTasks(Instant now) {
        recoverTimedOutRunningTasks(now);
        return tasks.values().stream()
                .filter(task -> task.getStatus() == DocumentIngestTaskStatus.PENDING
                        || task.getStatus() == DocumentIngestTaskStatus.RETRYING)
                .filter(task -> task.getNextRetryAt() == null || !task.getNextRetryAt().isAfter(now))
                .sorted(Comparator.comparing(DocumentIngestTask::getCreatedAt))
                .toList();
    }

    public synchronized boolean markRunning(String taskId) {
        return markRunning(taskId, "worker-local", Instant.now());
    }

    public synchronized boolean markRunning(String taskId, String workerId, Instant now) {
        DocumentIngestTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        if (task.getStatus() != DocumentIngestTaskStatus.PENDING
                && task.getStatus() != DocumentIngestTaskStatus.RETRYING) {
            return false;
        }
        if (task.getNextRetryAt() != null && task.getNextRetryAt().isAfter(now)) {
            return false;
        }
        task.setStatus(DocumentIngestTaskStatus.RUNNING);
        task.setLockedBy(workerId);
        task.setLockedAt(now);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setUpdatedAt(now);
        return true;
    }

    public synchronized void markSuccess(String taskId) {
        DocumentIngestTask task = tasks.get(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(DocumentIngestTaskStatus.SUCCESS);
        task.setCurrentStep(DocumentIngestStep.DONE);
        task.setLockedBy(null);
        task.setLockedAt(null);
        task.setNextRetryAt(null);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setUpdatedAt(Instant.now());
    }

    public synchronized void markFailure(String taskId, DocumentIngestStep currentStep, String errorCode, String errorMessage, boolean retryable) {
        DocumentIngestTask task = tasks.get(taskId);
        if (task == null) {
            return;
        }
        Instant now = Instant.now();
        task.setCurrentStep(currentStep);
        task.setErrorCode(errorCode);
        task.setErrorMessage(limit(errorMessage, 500));
        task.setLockedBy(null);
        task.setLockedAt(null);

        if (!retryable) {
            task.setStatus(DocumentIngestTaskStatus.FAILED);
            task.setNextRetryAt(null);
            task.setUpdatedAt(now);
            return;
        }

        int retryCount = task.getRetryCount() + 1;
        task.setRetryCount(retryCount);
        if (retryCount < task.getMaxRetry()) {
            task.setStatus(DocumentIngestTaskStatus.RETRYING);
            task.setNextRetryAt(now.plus(backoffForRetry(retryCount)));
        } else {
            task.setStatus(DocumentIngestTaskStatus.FAILED);
            task.setNextRetryAt(null);
        }
        task.setUpdatedAt(now);
    }

    public synchronized void markCanceled(String taskId, String reason) {
        DocumentIngestTask task = tasks.get(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(DocumentIngestTaskStatus.CANCELED);
        task.setLockedBy(null);
        task.setLockedAt(null);
        task.setNextRetryAt(null);
        task.setErrorCode("DOCUMENT_VERSION_STALE");
        task.setErrorMessage(limit(reason, 500));
        task.setUpdatedAt(Instant.now());
    }

    public synchronized void updateCurrentStep(String taskId, DocumentIngestStep currentStep) {
        DocumentIngestTask task = tasks.get(taskId);
        if (task == null) {
            return;
        }
        task.setCurrentStep(currentStep);
        task.setUpdatedAt(Instant.now());
    }

    private synchronized void recoverTimedOutRunningTasks(Instant now) {
        Instant expiredBefore = now.minus(DEFAULT_LOCK_TIMEOUT);
        for (DocumentIngestTask task : tasks.values()) {
            if (task.getStatus() == DocumentIngestTaskStatus.RUNNING
                    && task.getLockedAt() != null
                    && task.getLockedAt().isBefore(expiredBefore)) {
                task.setStatus(DocumentIngestTaskStatus.RETRYING);
                task.setLockedBy(null);
                task.setLockedAt(null);
                task.setNextRetryAt(now);
                task.setErrorCode("INGEST_LOCK_TIMEOUT");
                task.setErrorMessage("worker lock timed out");
                task.setUpdatedAt(now);
            }
        }
    }

    private static Duration backoffForRetry(int retryCount) {
        long delaySeconds = Math.min(300, 5L * (1L << Math.max(0, retryCount - 1)));
        return Duration.ofSeconds(delaySeconds);
    }

    private static String limit(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }
}
