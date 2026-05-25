package com.yhl.rag.agent;

import com.yhl.rag.tool.ToolResult;

public class AgentChatResponse {

    private String answer;

    private boolean toolCalled;

    private String toolName;

    private ToolResult toolResult;

    public AgentChatResponse() {
    }

    public AgentChatResponse(String answer, boolean toolCalled, String toolName, ToolResult toolResult) {
        this.answer = answer;
        this.toolCalled = toolCalled;
        this.toolName = toolName;
        this.toolResult = toolResult;
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
}
