package com.yhl.rag.tool;

import org.springframework.http.HttpStatus;

public class ToolException extends RuntimeException {

    private final String errorType;

    private final String toolName;

    private final HttpStatus httpStatus;

    public ToolException(String errorType, String message) {
        this(errorType, message, null, HttpStatus.BAD_REQUEST);
    }

    public ToolException(String errorType, String message, String toolName) {
        this(errorType, message, toolName, HttpStatus.BAD_REQUEST);
    }

    public ToolException(String errorType, String message, String toolName, HttpStatus httpStatus) {
        super(message);
        this.errorType = errorType;
        this.toolName = toolName;
        this.httpStatus = httpStatus;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getToolName() {
        return toolName;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
