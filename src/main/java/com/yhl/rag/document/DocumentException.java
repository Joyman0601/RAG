package com.yhl.rag.document;

public class DocumentException extends RuntimeException {

    private final String errorType;

    public DocumentException(String errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public DocumentException(String errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public String getErrorType() {
        return errorType;
    }
}
