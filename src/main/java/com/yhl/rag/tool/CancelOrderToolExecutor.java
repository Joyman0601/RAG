package com.yhl.rag.tool;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.agent.AgentErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CancelOrderToolExecutor implements ToolExecutor<CancelOrderToolRequest> {

    private static final String TOOL_NAME = "cancel_order";

    private final ToolDefinition definition;

    public CancelOrderToolExecutor(ObjectMapper objectMapper) {
        this.definition = new ToolDefinition(
                TOOL_NAME,
                "Cancel a mock order by order id. This is a high risk operation and requires user confirmation.",
                buildParameterSchema(objectMapper),
                "order:cancel",
                RiskLevel.HIGH
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
    public Class<CancelOrderToolRequest> getRequestClass() {
        return CancelOrderToolRequest.class;
    }

    @Override
    public Object execute(CancelOrderToolRequest request, ToolExecutionContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new ToolException("PERMISSION_DENIED", "current user is required", TOOL_NAME, HttpStatus.BAD_REQUEST);
        }
        if ("ORD_NOT_FOUND".equals(request.getOrderId())) {
            throw new ToolException(AgentErrorCode.BUSINESS_REJECTED.name(), "order not found", TOOL_NAME, HttpStatus.BAD_REQUEST);
        }
        return new CancelOrderToolResult(
                request.getOrderId(),
                "CANCELLED",
                OffsetDateTime.now(ZoneOffset.ofHours(8)).toString()
        );
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
