package com.yhl.rag.tool;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.agent.AgentErrorCode;
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
                buildParameterSchema(objectMapper),
                "order:query",
                RiskLevel.LOW
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
            throw new ToolException("PERMISSION_DENIED", "current user is required", TOOL_NAME, HttpStatus.BAD_REQUEST);
        }

        if ("ORD_NOT_FOUND".equals(request.getOrderId())) {
            throw new ToolException(AgentErrorCode.BUSINESS_REJECTED.name(), "order not found", TOOL_NAME, HttpStatus.BAD_REQUEST);
        }

        if (!request.getOrderId().startsWith(context.getUserId() + "_")
                && !isPublicMockOrder(request.getOrderId())) {
            throw new ToolException("PERMISSION_DENIED", "current user is not allowed to access this order", TOOL_NAME, HttpStatus.BAD_REQUEST);
        }

        String logisticsStatus = mockLogisticsStatus(request.getOrderId());
        return new QueryOrderToolResult(
                request.getOrderId(),
                "PAID",
                new BigDecimal("199.90"),
                "2026-05-20T10:30:00+08:00",
                logisticsStatus
        );
    }

    private boolean isPublicMockOrder(String orderId) {
        return "ORD001".equals(orderId)
                || "ORD002".equals(orderId)
                || "ORD202605220001".equals(orderId)
                || "123456".equals(orderId);
    }

    private String mockLogisticsStatus(String orderId) {
        if ("ORD002".equals(orderId)
                || "123456".equals(orderId)
                || orderId.endsWith("_UNSHIPPED")) {
            return "NOT_SHIPPED";
        }
        return "SHIPPED";
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
