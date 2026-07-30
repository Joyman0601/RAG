package com.yhl.rag.document;

public enum DocumentIngestTaskStatus {
    PENDING,
    RUNNING,
    RETRYING,
    FAILED,
    SUCCESS,
    CANCELED
}
