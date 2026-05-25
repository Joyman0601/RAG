package com.yhl.rag.tool;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class QueryOrderToolExecutor implements ToolExecutor<QueryOrderToolRequest> {

    private static final String TOOL_NAME = "query_order";

    private final ToolDefinition definition;

    public QueryOrderToolExecutor(ObjectMapper objectMapper) {
        this.definition = new ToolDefinition(
                TOOL_NAME,
                "Query mock order data by order id. User identity comes from backend context.",
                buildParameterSchema(objectMapper)
        );
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public Class<QueryOrderToolRequest> getRequestClass() {
        return QueryOrderToolRequest.class;
    }

    @Override
    public Object execute(QueryOrderToolRequest request, ToolExecutionContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new ToolException("TOOL_CONTEXT_INVALID", "current user is required", TOOL_NAME, HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", request.getOrderId());
        data.put("status", "PAID");
        data.put("amount", new BigDecimal("199.90"));
        data.put("createdAt", "2026-05-20T10:30:00+08:00");
        return data;
    }

    private com.fasterxml.jackson.databind.JsonNode buildParameterSchema(ObjectMapper objectMapper) {
        com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        com.fasterxml.jackson.databind.node.ObjectNode properties = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode orderId = objectMapper.createObjectNode();
        orderId.put("type", "string");
        orderId.put("description", "Order id. Only letters, numbers, hyphen and underscore are allowed.");
        orderId.put("maxLength", 64);
        orderId.put("pattern", "^[A-Za-z0-9_-]+$");
        properties.set("orderId", orderId);

        schema.set("properties", properties);
        schema.putArray("required").add("orderId");
        return schema;
    }
}
