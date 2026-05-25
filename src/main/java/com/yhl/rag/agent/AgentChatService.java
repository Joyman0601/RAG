package com.yhl.rag.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.tool.ToolDefinition;
import com.yhl.rag.tool.ToolExecutionContext;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolRegistry;
import com.yhl.rag.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private static final String TOOL_DECISION_PROMPT = """
            你是一个企业客服 Agent。你不能直接执行工具，只能向后端提出工具调用请求。

            后端当前暴露给你的工具如下：
            %s

            工具选择规则：
            - 如果用户要查询订单且提供了订单号，可以调用 query_order。
            - 如果用户要查询订单但缺少订单号，需要追问用户提供订单号，不要调用工具。
            - 如果用户不是在查询订单，不要调用工具，直接回答。
            - 每次只能返回 0 或 1 次工具调用。
            - 不要把 userId 放入工具参数，真实用户身份由后端提供。

            你必须只返回 JSON，不要返回 markdown，不要解释。
            返回格式只能是以下二选一：
            {"answer":"直接回复用户的话","toolCall":null}
            {"answer":null,"toolCall":{"toolName":"query_order","arguments":{"orderId":"订单号"}}}
            """;

    private static final String FINAL_ANSWER_PROMPT = """
            你是一个企业客服 Agent。你已经收到后端工具执行结果。
            请基于工具结果生成给用户的自然语言回答。
            如果工具执行失败，请用简洁、安全的方式说明失败原因，并引导用户补充或修正信息。
            不要编造工具结果中不存在的订单字段。
            """;

    private final LlmClient llmClient;

    private final ToolRegistry toolRegistry;

    private final ToolExecutionService toolExecutionService;

    private final AllowedToolService allowedToolService;

    private final ObjectMapper objectMapper;

    public AgentChatService(
            LlmClient llmClient,
            ToolRegistry toolRegistry,
            ToolExecutionService toolExecutionService,
            AllowedToolService allowedToolService,
            ObjectMapper objectMapper
    ) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.allowedToolService = allowedToolService;
        this.objectMapper = objectMapper;
    }

    public AgentChatResponse chat(String message) {
        ToolExecutionContext context = new ToolExecutionContext(
                UUID.randomUUID().toString(),
                "user_001",
                "mock-department",
                1
        );
        List<String> allowedToolNames = allowedToolService.allowedToolNames(context);

        log.info("agent_chat started: requestId={}, question={}", context.getRequestId(), sanitizeForLog(message));

        String rawDecision = llmClient.generate(
                TOOL_DECISION_PROMPT.formatted(serializeAllowedToolDefinitions(allowedToolNames)),
                List.of(new LlmMessage("user", message))
        );

        AgentToolDecision decision = parseDecision(rawDecision);
        AgentToolDecision.ToolCall toolCall = decision.getToolCall();
        boolean modelSelectedTool = toolCall != null && StringUtils.hasText(toolCall.getToolName());
        log.info("agent_chat model_decision: requestId={}, selectedTool={}, toolName={}",
                context.getRequestId(),
                modelSelectedTool,
                modelSelectedTool ? toolCall.getToolName() : null);

        if (!modelSelectedTool) {
            return new AgentChatResponse(decision.getAnswer(), false, null, null);
        }

        String toolName = toolCall.getToolName().trim();
        if (!allowedToolNames.contains(toolName)) {
            log.warn("agent_chat blocked_tool: requestId={}, toolName={}, reason=NOT_ALLOWED",
                    context.getRequestId(),
                    toolName);
            return new AgentChatResponse("当前用户无权调用该工具。", false, toolName, null);
        }

        ToolResult toolResult = toolExecutionService.execute(toolName, toolCall.getArguments(), context);
        log.info("agent_chat tool_executed: requestId={}, toolName={}, success={}, elapsedMs={}",
                context.getRequestId(),
                toolName,
                toolResult.isSuccess(),
                toolResult.getElapsedMs());

        String finalAnswer = llmClient.generate(
                FINAL_ANSWER_PROMPT,
                buildFinalAnswerMessages(message, rawDecision, toolResult)
        );

        return new AgentChatResponse(finalAnswer, true, toolName, toolResult);
    }

    private String serializeAllowedToolDefinitions(List<String> allowedToolNames) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (String toolName : allowedToolNames) {
            toolRegistry.findDefinition(toolName).ifPresent(definitions::add);
        }

        try {
            return objectMapper.writeValueAsString(definitions);
        } catch (JsonProcessingException exception) {
            throw new LlmException(
                    LlmErrorType.INVALID_STRUCTURED_OUTPUT,
                    "工具定义序列化失败",
                    exception
            );
        }
    }

    private AgentToolDecision parseDecision(String rawDecision) {
        JsonNode root;
        try {
            root = objectMapper.readTree(cleanJson(rawDecision));
        } catch (JsonProcessingException exception) {
            throw new LlmException(
                    LlmErrorType.INVALID_STRUCTURED_OUTPUT,
                    "Agent 工具选择结果不是合法 JSON",
                    exception
            );
        }

        JsonNode toolCalls = root.get("toolCalls");
        if (toolCalls != null && toolCalls.isArray() && toolCalls.size() > 1) {
            throw new LlmException(
                    LlmErrorType.INVALID_STRUCTURED_OUTPUT,
                    "Agent 每轮请求最多只能选择一个工具"
            );
        }
        if (toolCalls != null && toolCalls.isArray() && toolCalls.size() == 1 && root.get("toolCall") == null) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).set("toolCall", toolCalls.get(0));
        }

        try {
            return objectMapper.treeToValue(root, AgentToolDecision.class);
        } catch (JsonProcessingException exception) {
            throw new LlmException(
                    LlmErrorType.INVALID_STRUCTURED_OUTPUT,
                    "Agent 工具选择结果结构不合法",
                    exception
            );
        }
    }

    private List<LlmMessage> buildFinalAnswerMessages(String userMessage, String rawDecision, ToolResult toolResult) {
        Map<String, Object> toolMessage = new LinkedHashMap<>();
        toolMessage.put("toolName", toolResult.getToolName());
        toolMessage.put("success", toolResult.isSuccess());
        toolMessage.put("result", toolResult.getResult());
        toolMessage.put("errorCode", toolResult.getErrorCode());
        toolMessage.put("errorMessage", toolResult.getErrorMessage());

        try {
            return List.of(
                    new LlmMessage("user", userMessage),
                    new LlmMessage("assistant", cleanJson(rawDecision)),
                    new LlmMessage("user", "tool message:\n" + objectMapper.writeValueAsString(toolMessage))
            );
        } catch (JsonProcessingException exception) {
            throw new LlmException(
                    LlmErrorType.INVALID_STRUCTURED_OUTPUT,
                    "工具结果序列化失败",
                    exception
            );
        }
    }

    private static String cleanJson(String rawOutput) {
        if (rawOutput == null) {
            return null;
        }

        String cleaned = rawOutput.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length()).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length()).trim();
        }
        return cleaned;
    }

    private static String sanitizeForLog(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200);
    }
}
