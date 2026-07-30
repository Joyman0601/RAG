package com.yhl.rag.llm;

public class LlmException extends RuntimeException {

    private final LlmErrorType errorType;

    public LlmException(LlmErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public LlmException(LlmErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public LlmErrorType getErrorType() {
        return errorType;
    }
}
