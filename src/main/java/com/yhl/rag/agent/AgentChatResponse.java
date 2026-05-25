package com.yhl.rag.agent;

import java.util.List;

import com.yhl.rag.tool.ToolResult;

public class AgentChatResponse {

    private String answer;

    private boolean toolCalled;

    private String toolName;

    private ToolResult toolResult;

    private String requestId;

    private String conversationId;

    private boolean requiresConfirmation;

    private String confirmationId;

    private String confirmationMessage;

    private List<AgentStep> steps;

    private String stopReason;

    public AgentChatResponse() {
    }

    public AgentChatResponse(String answer, boolean toolCalled, String toolName, ToolResult toolResult, String requestId) {
        this(answer, toolCalled, toolName, toolResult, requestId, null, false, null, null);
    }

    public AgentChatResponse(
            String answer,
            boolean toolCalled,
            String toolName,
            ToolResult toolResult,
            String requestId,
            String conversationId,
            boolean requiresConfirmation,
            String confirmationId,
            String confirmationMessage
    ) {
        this(answer, toolCalled, toolName, toolResult, requestId, conversationId, requiresConfirmation, confirmationId, confirmationMessage, null, null);
    }

    public AgentChatResponse(
            String answer,
            boolean toolCalled,
            String toolName,
            ToolResult toolResult,
            String requestId,
            String conversationId,
            boolean requiresConfirmation,
            String confirmationId,
            String confirmationMessage,
            List<AgentStep> steps,
            String stopReason
    ) {
        this.answer = answer;
        this.toolCalled = toolCalled;
        this.toolName = toolName;
        this.toolResult = toolResult;
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.requiresConfirmation = requiresConfirmation;
        this.confirmationId = confirmationId;
        this.confirmationMessage = confirmationMessage;
        this.steps = steps;
        this.stopReason = stopReason;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public boolean isToolCalled() {
        return toolCalled;
    }

    public void setToolCalled(boolean toolCalled) {
        this.toolCalled = toolCalled;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public ToolResult getToolResult() {
        return toolResult;
    }

    public void setToolResult(ToolResult toolResult) {
        this.toolResult = toolResult;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getConfirmationId() {
        return confirmationId;
    }

    public void setConfirmationId(String confirmationId) {
        this.confirmationId = confirmationId;
    }

    public String getConfirmationMessage() {
        return confirmationMessage;
    }

    public void setConfirmationMessage(String confirmationMessage) {
        this.confirmationMessage = confirmationMessage;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentStep> steps) {
        this.steps = steps;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }
}
