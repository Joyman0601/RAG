package com.yhl.rag.agent;

import java.util.List;

import com.yhl.rag.tool.ToolResult;

public class AgentLoopResponse {

    private String answer;

    private boolean finished;

    private String stopReason;

    private List<AgentStep> steps;

    private List<ToolResult> toolResults;

    private boolean requiresConfirmation;

    private String confirmationId;

    private String requestId;

    private String conversationId;

    private AgentToolDebugInfo toolDebugInfo;

    public AgentLoopResponse() {
    }

    public AgentLoopResponse(String answer, boolean finished, String stopReason, List<AgentStep> steps, List<ToolResult> toolResults, boolean requiresConfirmation, String confirmationId, String requestId) {
        this(answer, finished, stopReason, steps, toolResults, requiresConfirmation, confirmationId, requestId, null);
    }

    public AgentLoopResponse(String answer, boolean finished, String stopReason, List<AgentStep> steps, List<ToolResult> toolResults, boolean requiresConfirmation, String confirmationId, String requestId, String conversationId) {
        this(answer, finished, stopReason, steps, toolResults, requiresConfirmation, confirmationId, requestId, conversationId, null);
    }

    public AgentLoopResponse(String answer, boolean finished, String stopReason, List<AgentStep> steps, List<ToolResult> toolResults, boolean requiresConfirmation, String confirmationId, String requestId, String conversationId, AgentToolDebugInfo toolDebugInfo) {
        this.answer = answer;
        this.finished = finished;
        this.stopReason = stopReason;
        this.steps = steps;
        this.toolResults = toolResults;
        this.requiresConfirmation = requiresConfirmation;
        this.confirmationId = confirmationId;
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.toolDebugInfo = toolDebugInfo;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentStep> steps) {
        this.steps = steps;
    }

    public List<ToolResult> getToolResults() {
        return toolResults;
    }

    public void setToolResults(List<ToolResult> toolResults) {
        this.toolResults = toolResults;
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

    public AgentToolDebugInfo getToolDebugInfo() {
        return toolDebugInfo;
    }

    public void setToolDebugInfo(AgentToolDebugInfo toolDebugInfo) {
        this.toolDebugInfo = toolDebugInfo;
    }
}
