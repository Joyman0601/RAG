package com.yhl.rag.document;

public class DocumentUploadResponse {

    private String documentId;

    private String taskId;

    public DocumentUploadResponse() {
    }

    public DocumentUploadResponse(String documentId, String taskId) {
        this.documentId = documentId;
        this.taskId = taskId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
}
