package com.yhl.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.TestSupport;
import com.yhl.rag.agent.AgentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolExecutionServiceTest {

    private ObjectMapper objectMapper;

    private ToolExecutionService toolExecutionService;

    @BeforeEach
    void setUp() {
        objectMapper = TestSupport.objectMapper();
        ToolRegistry registry = TestSupport.toolRegistry(objectMapper, new ThrowingToolExecutor(objectMapper));
        toolExecutionService = TestSupport.toolExecutionService(registry, objectMapper);
    }

    @Test
    void queryOrder_whenOrderIdIsValid_returnsStructuredResultWithoutSensitiveFields() {
        ToolResult result = toolExecutionService.execute("query_order", json("""
                {"orderId":"ORD001"}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isInstanceOf(QueryOrderToolResult.class);
        QueryOrderToolResult order = (QueryOrderToolResult) result.getResult();
        assertThat(order.getOrderId()).isEqualTo("ORD001");
        assertThat(order.getLogisticsStatus()).isEqualTo("SHIPPED");

        JsonNode resultNode = objectMapper.valueToTree(result.getResult());
        assertThat(resultNode.has("phone")).isFalse();
        assertThat(resultNode.has("mobile")).isFalse();
        assertThat(resultNode.has("address")).isFalse();
        assertThat(resultNode.has("paymentSerialNo")).isFalse();
        assertThat(resultNode.has("internalRemark")).isFalse();
    }

    @Test
    void queryOrder_whenOrderIdIsBlank_returnsValidationError() {
        ToolResult result = toolExecutionService.execute("query_order", json("""
                {"orderId":""}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AgentErrorCode.VALIDATION_ERROR.name());
    }

    @Test
    void queryOrder_whenOrderIdFormatIsInvalid_returnsValidationError() {
        ToolResult result = toolExecutionService.execute("query_order", json("""
                {"orderId":"ORD#001"}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AgentErrorCode.VALIDATION_ERROR.name());
    }

    @Test
    void queryOrder_whenUserIdIsProvidedInArguments_rejectsPrivilegeEscalation() {
        ToolResult result = toolExecutionService.execute("query_order", json("""
                {"orderId":"ORD001","userId":"user_002"}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AgentErrorCode.PERMISSION_DENIED.name());
    }

    @Test
    void searchKnowledge_whenTopKIsProvided_allowsModelControlledLimitOnly() {
        ToolResult result = toolExecutionService.execute("search_knowledge_base", json("""
                {"query":"退款政策","topK":3}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void searchKnowledge_whenTenantOrDepartmentIsProvided_rejectsPrivilegeEscalation() {
        ToolResult tenantResult = toolExecutionService.execute("search_knowledge_base", json("""
                {"query":"退款政策","tenantId":"tenant-other"}
                """), TestSupport.context());
        ToolResult departmentResult = toolExecutionService.execute("search_knowledge_base", json("""
                {"query":"退款政策","departmentId":"finance"}
                """), TestSupport.context());

        assertThat(tenantResult.isSuccess()).isFalse();
        assertThat(tenantResult.getErrorCode()).isEqualTo(AgentErrorCode.PERMISSION_DENIED.name());
        assertThat(departmentResult.isSuccess()).isFalse();
        assertThat(departmentResult.getErrorCode()).isEqualTo(AgentErrorCode.PERMISSION_DENIED.name());
    }

    @Test
    void execute_whenToolNameIsUnknown_returnsUnknownTool() {
        ToolResult result = toolExecutionService.execute("admin_query_user", json("""
                {"userId":"user_002"}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AgentErrorCode.UNKNOWN_TOOL.name());
    }

    @Test
    void execute_whenRequiredArgumentIsMissing_returnsValidationError() {
        ToolResult result = toolExecutionService.execute("query_order", json("""
                {}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AgentErrorCode.VALIDATION_ERROR.name());
    }

    @Test
    void execute_whenArgumentFormatIsWrong_returnsValidationError() {
        ToolResult result = toolExecutionService.execute("query_order", json("""
                {"orderId":{}}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AgentErrorCode.VALIDATION_ERROR.name());
    }

    @Test
    void execute_whenPermissionIsMissing_returnsPermissionDenied() {
        ToolResult result = toolExecutionService.execute("query_order", json("""
                {"orderId":"ORD001"}
                """), TestSupport.contextWithout("order:query"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AgentErrorCode.PERMISSION_DENIED.name());
    }

    @Test
    void execute_whenExecutorThrowsUnexpectedException_returnsToolExecutionFailed() {
        ToolResult result = toolExecutionService.execute("throwing_tool", json("""
                {}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AgentErrorCode.TOOL_EXECUTION_FAILED.name());
    }

    @Test
    void execute_whenSuccessful_setsSuccessAndElapsedMs() {
        ToolResult result = toolExecutionService.execute("query_order", json("""
                {"orderId":"ORD001"}
                """), TestSupport.context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getElapsedMs()).isGreaterThanOrEqualTo(0);
    }

    private JsonNode json(String rawJson) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    static class ThrowingToolExecutor implements ToolExecutor<ThrowingRequest> {

        private final ToolDefinition definition;

        ThrowingToolExecutor(ObjectMapper objectMapper) {
            com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            this.definition = new ToolDefinition("throwing_tool", "Throws for tests.", schema, null, RiskLevel.LOW);
        }

        @Override
        public String getName() {
            return "throwing_tool";
        }

        @Override
        public ToolDefinition getDefinition() {
            return definition;
        }

        @Override
        public Class<ThrowingRequest> getRequestClass() {
            return ThrowingRequest.class;
        }

        @Override
        public Object execute(ThrowingRequest request, ToolExecutionContext context) {
            throw new IllegalStateException("boom");
        }
    }

    static class ThrowingRequest {
    }
}
