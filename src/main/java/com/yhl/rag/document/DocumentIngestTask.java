package com.yhl.rag.document;

import java.time.Instant;

public class DocumentIngestTask {

    private String taskId;

    private String documentId;

    private int documentVersion;

    private DocumentIngestTaskStatus status;

    private DocumentIngestStep currentStep;

    private int retryCount;

    private int maxRetry;

    private Instant nextRetryAt;

    private String lockedBy;

    private Instant lockedAt;

    private String errorCode;

    private String errorMessage;

    private Instant createdAt;

    private Instant updatedAt;

    public DocumentIngestTask() {
    }

    public DocumentIngestTask(String taskId, String documentId, int maxRetry) {
        this(taskId, documentId, 1, maxRetry);
    }

    public DocumentIngestTask(String taskId, String documentId, int documentVersion, int maxRetry) {
        Instant now = Instant.now();
        this.taskId = taskId;
        this.documentId = documentId;
        this.documentVersion = documentVersion;
        this.status = DocumentIngestTaskStatus.PENDING;
        this.currentStep = DocumentIngestStep.SAVE_FILE;
        this.maxRetry = maxRetry;
        this.nextRetryAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public int getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(int documentVersion) {
        this.documentVersion = documentVersion;
    }

    public DocumentIngestTaskStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentIngestTaskStatus status) {
        this.status = status;
    }

    public DocumentIngestStep getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(DocumentIngestStep currentStep) {
        this.currentStep = currentStep;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
