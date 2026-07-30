package com.yhl.rag.tool;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolDefinition {

    private String name;

    private String description;

    private JsonNode parameterSchema;

    private String permissionCode;

    private RiskLevel riskLevel;

    public ToolDefinition() {
    }

    public ToolDefinition(String name, String description, JsonNode parameterSchema) {
        this(name, description, parameterSchema, null, RiskLevel.LOW);
    }

    public ToolDefinition(String name, String description, JsonNode parameterSchema, String permissionCode, RiskLevel riskLevel) {
        this.name = name;
        this.description = description;
        this.parameterSchema = parameterSchema;
        this.permissionCode = permissionCode;
        this.riskLevel = riskLevel;
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

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }
}
