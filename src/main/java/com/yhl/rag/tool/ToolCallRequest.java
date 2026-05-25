package com.yhl.rag.tool;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolCallRequest {

    private String toolName;

    private JsonNode arguments;

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public JsonNode getArguments() {
        return arguments;
    }

    public void setArguments(JsonNode arguments) {
        this.arguments = arguments;
    }
}
