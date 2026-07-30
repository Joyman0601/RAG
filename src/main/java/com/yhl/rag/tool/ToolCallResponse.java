package com.yhl.rag.tool;

public class ToolCallResponse {

    private String toolName;

    private boolean success;

    private Object result;

    private String errorMessage;

    public ToolCallResponse() {
    }

    public ToolCallResponse(String toolName, boolean success, Object result, String errorMessage) {
        this.toolName = toolName;
        this.success = success;
        this.result = result;
        this.errorMessage = errorMessage;
    }

    public static ToolCallResponse success(String toolName, Object result) {
        return new ToolCallResponse(toolName, true, result, null);
    }

    public static ToolCallResponse failure(String toolName, String errorMessage) {
        return new ToolCallResponse(toolName, false, null, errorMessage);
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

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
