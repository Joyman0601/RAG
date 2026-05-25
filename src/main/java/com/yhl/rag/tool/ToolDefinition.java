package com.yhl.rag.tool;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolDefinition {

    private String name;

    private String description;

    private JsonNode parameterSchema;

    public ToolDefinition() {
    }

    public ToolDefinition(String name, String description, JsonNode parameterSchema) {
        this.name = name;
        this.description = description;
        this.parameterSchema = parameterSchema;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getParameterSchema() {
        return parameterSchema;
    }

    public void setParameterSchema(JsonNode parameterSchema) {
        this.parameterSchema = parameterSchema;
    }
}
