package com.yhl.rag.tool;

public class ToolResult {

    private String toolName;

    private boolean success;

    private Object result;

    private String errorCode;

    private String errorMessage;

    private long elapsedMs;

    public ToolResult() {
    }

    public ToolResult(String toolName, boolean success, Object result, String errorCode, String errorMessage, long elapsedMs) {
        this.toolName = toolName;
        this.success = success;
        this.result = result;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.elapsedMs = elapsedMs;
    }

    public static ToolResult success(String toolName, Object result, long elapsedMs) {
        return new ToolResult(toolName, true, result, null, null, elapsedMs);
    }

    public static ToolResult failure(String toolName, String errorCode, String errorMessage, long elapsedMs) {
        return new ToolResult(toolName, false, null, errorCode, errorMessage, elapsedMs);
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
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

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}
