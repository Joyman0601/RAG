package com.yhl.rag;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.tool.CancelOrderToolExecutor;
import com.yhl.rag.tool.QueryOrderToolExecutor;
import com.yhl.rag.tool.RiskLevel;
import com.yhl.rag.tool.SearchKnowledgeToolRequest;
import com.yhl.rag.tool.SearchKnowledgeToolResult;
import com.yhl.rag.tool.ToolDefinition;
import com.yhl.rag.tool.ToolExecutionContext;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolExecutor;
import com.yhl.rag.tool.ToolRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

public final class TestSupport {

    private TestSupport() {
    }

    public static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    public static Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }

    public static ToolExecutionContext context() {
        return new ToolExecutionContext(
                "req-test",
                "user_001",
                "default-department",
                1
        );
    }

    public static ToolExecutionContext contextWithout(String permission) {
        Set<String> permissions = Set.of("order:query", "order:cancel", "knowledge:search");
        permissions = permissions.stream()
                .filter(item -> !item.equals(permission))
                .collect(java.util.stream.Collectors.toSet());
        return new ToolExecutionContext(
                "req-test",
                "user_001",
                "default-department",
                1,
                Set.of("customer"),
                permissions
        );
    }

    public static ToolRegistry toolRegistry(ObjectMapper objectMapper, ToolExecutor<?>... extraExecutors) {
        java.util.ArrayList<ToolExecutor<?>> executors = new java.util.ArrayList<>();
        executors.add(new QueryOrderToolExecutor(objectMapper));
        executors.add(new CancelOrderToolExecutor(objectMapper));
        executors.add(new FakeSearchKnowledgeToolExecutor(objectMapper));
        executors.addAll(List.of(extraExecutors));
        return new ToolRegistry(executors);
    }

    public static ToolExecutionService toolExecutionService(ToolRegistry registry, ObjectMapper objectMapper) {
        return new ToolExecutionService(registry, objectMapper, validator());
    }

    public static LlmGenerationResult llm(String answer) {
        return new LlmGenerationResult(answer, 10, 5, 15);
    }

    public static class FakeSearchKnowledgeToolExecutor implements ToolExecutor<SearchKnowledgeToolRequest> {

        private static final String TOOL_NAME = "search_knowledge_base";

        private final ToolDefinition definition;

        public FakeSearchKnowledgeToolExecutor(ObjectMapper objectMapper) {
            com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            com.fasterxml.jackson.databind.node.ObjectNode properties = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode query = objectMapper.createObjectNode();
            query.put("type", "string");
            properties.set("query", query);
            schema.set("properties", properties);
            schema.putArray("required").add("query");
            this.definition = new ToolDefinition(
                    TOOL_NAME,
                    "Fake knowledge search for tests.",
                    schema,
                    "knowledge:search",
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
        public Class<SearchKnowledgeToolRequest> getRequestClass() {
            return SearchKnowledgeToolRequest.class;
        }

        @Override
        public Object execute(SearchKnowledgeToolRequest request, ToolExecutionContext context) {
            return new SearchKnowledgeToolResult(
                    true,
                    List.of(new SearchKnowledgeToolResult.Context(
                            "未发货订单允许申请退款；已发货订单不允许自动退款，需要人工处理。",
                            "source-1",
                            "doc-1",
                            "refund-policy",
                            0.95
                    )),
                    List.of(new SearchKnowledgeToolResult.Source("doc-1", "refund-policy", 0)),
                    1
            );
        }
    }
}
