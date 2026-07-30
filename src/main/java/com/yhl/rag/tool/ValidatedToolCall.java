package com.yhl.rag.tool;

import com.fasterxml.jackson.databind.JsonNode;

public class ValidatedToolCall {

    private final String toolName;

    private final ToolDefinition definition;

    private final JsonNode validatedArguments;

    public ValidatedToolCall(String toolName, ToolDefinition definition, JsonNode validatedArguments) {
        this.toolName = toolName;
        this.definition = definition;
        this.validatedArguments = validatedArguments;
    }

    public String getToolName() {
        return toolName;
    }

    public ToolDefinition getDefinition() {
        return definition;
    }

    public JsonNode getValidatedArguments() {
        return validatedArguments;
    }
}
