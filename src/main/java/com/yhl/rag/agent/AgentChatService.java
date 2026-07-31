package com.yhl.rag.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.cost.CostGovernanceService;
import com.yhl.rag.cost.CostProperties;
import com.yhl.rag.cost.ModelTier;
import com.yhl.rag.cost.QuotaService;
import com.yhl.rag.cost.RateLimitService;
import com.yhl.rag.cost.TokenEstimator;
import com.yhl.rag.cost.UsageRecordService;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.observability.RequestContext;
import com.yhl.rag.tool.ToolDefinition;
import com.yhl.rag.tool.RiskLevel;
import com.yhl.rag.tool.ToolExecutionContext;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolException;
import com.yhl.rag.tool.ToolRegistry;
import com.yhl.rag.tool.ToolResult;
import com.yhl.rag.tool.SearchKnowledgeToolResult;
import com.yhl.rag.tool.ValidatedToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
            - 如果用户明确要求取消订单且提供了订单号，可以调用 cancel_order。
            - 如果用户问题需要查询企业知识库、制度文档、产品文档或内部资料，可以调用 search_knowledge_base。
            - search_knowledge_base 不能用于查询订单、用户隐私或实时业务数据。
            - 如果用户要查询订单但缺少订单号，需要追问用户提供订单号，不要调用工具。
            - 如果用户既不是查询订单，也不是查询企业知识库，不要调用工具，直接回答。
            - 每次只能返回 0 或 1 次工具调用。
            - 不要把 userId、tenantId、topK、scoreThreshold 放入工具参数，真实用户身份和检索范围由后端提供。

            你必须只返回 JSON，不要返回 markdown，不要解释。
            返回格式只能是以下二选一：
            {"answer":"直接回复用户的话","toolCall":null}
            {"answer":null,"toolCall":{"toolName":"query_order或cancel_order或search_knowledge_base","arguments":{"orderId":"订单号或query字段"}}}
            """;

    private static final String FINAL_ANSWER_PROMPT = """
            你是一个企业客服 Agent。你已经收到后端工具执行结果。
            只能基于工具返回结果生成给用户的自然语言回答。
            不要编造工具结果中不存在的信息。
            如果工具执行失败，请用简洁、安全的方式说明失败原因，并引导用户补充或修正信息。
            默认不要输出内部错误码；只有用户明确要求时才可以提及 errorCode。
            """;

    private final LlmClient llmClient;

    private final LlmProperties llmProperties;

    private final ToolRegistry toolRegistry;

    private final ToolExecutionService toolExecutionService;

    private final AllowedToolService allowedToolService;

    private final AgentToolRolloutService rolloutService;

    private final ShadowToolDecisionService shadowDecisionService;

    private final ConfirmationService confirmationService;

    private final ConversationStateService conversationStateService;

    private final AgentContextBuilder agentContextBuilder;

    private final ObjectMapper objectMapper;

    private final CostGovernanceService costGovernanceService;

    @Autowired
    public AgentChatService(
            LlmClient llmClient,
            LlmProperties llmProperties,
            ToolRegistry toolRegistry,
            ToolExecutionService toolExecutionService,
            AllowedToolService allowedToolService,
            AgentToolRolloutService rolloutService,
            ShadowToolDecisionService shadowDecisionService,
            ConfirmationService confirmationService,
            ConversationStateService conversationStateService,
            AgentContextBuilder agentContextBuilder,
            ObjectMapper objectMapper,
            CostGovernanceService costGovernanceService
    ) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.allowedToolService = allowedToolService;
        this.rolloutService = rolloutService;
        this.shadowDecisionService = shadowDecisionService;
        this.confirmationService = confirmationService;
        this.conversationStateService = conversationStateService;
        this.agentContextBuilder = agentContextBuilder;
        this.objectMapper = objectMapper;
        this.costGovernanceService = costGovernanceService;
    }

    public AgentChatService(
            LlmClient llmClient,
            LlmProperties llmProperties,
            ToolRegistry toolRegistry,
            ToolExecutionService toolExecutionService,
            AllowedToolService allowedToolService,
            ConfirmationService confirmationService,
            ConversationStateService conversationStateService,
            AgentContextBuilder agentContextBuilder,
            ObjectMapper objectMapper
    ) {
        this(
                llmClient,
                llmProperties,
                toolRegistry,
                toolExecutionService,
                allowedToolService,
                new AgentToolRolloutService(),
                new ShadowToolDecisionService(objectMapper),
                confirmationService,
                conversationStateService,
                agentContextBuilder,
                objectMapper,
                defaultCostGovernanceService(llmProperties)
        );
    }

    public AgentChatResponse chat(String conversationId, String message) {
        ToolExecutionContext context = mockContext();
        ConversationState state = conversationStateService.getOrCreate(conversationId, context.getUserId());
        boolean stateUpdated = conversationStateService.updateAfterUserMessage(state, message);
        List<String> allowedToolNames = allowedToolService.allowedToolNames(context);
        List<AgentStep> steps = new ArrayList<>();

        log.info("agent_chat started: requestId={}, conversationId={}, userId={}, stateUpdated={}, question={}",
                context.getRequestId(),
                state.getConversationId(),
                context.getUserId(),
                stateUpdated,
                sanitizeForLog(message));

        String rawDecision;
        AgentToolDecision decision;
        try {
            List<LlmMessage> decisionMessages = agentContextBuilder.buildMessages(context.getUserId(), state.getConversationId(), message, allowedToolNames);
            long decisionStartedAt = System.nanoTime();
            costGovernanceService.checkBeforeLlm(
                    context.getTenantId(),
                    context.getUserId(),
                    "AGENT_CHAT_DECISION",
                    TOOL_DECISION_PROMPT.formatted(serializeAllowedToolDefinitions(allowedToolNames)),
                    decisionMessages,
                    costGovernanceService.properties().getAgentMaxInputTokens(),
                    llmProperties.getMaxOutputTokens()
            );
            LlmGenerationResult decisionResult = llmClient.generateWithUsage(
                    TOOL_DECISION_PROMPT.formatted(serializeAllowedToolDefinitions(allowedToolNames)),
                    decisionMessages
            );
            rawDecision = decisionResult.getAnswer();
            decision = parseDecision(rawDecision);
            boolean hasToolCall = decision.getToolCall() != null && StringUtils.hasText(decision.getToolCall().getToolName());
            long elapsedMs = elapsedMillis(decisionStartedAt);
            costGovernanceService.recordUsage(
                    context.getRequestId(),
                    context.getTenantId(),
                    context.getUserId(),
                    "AGENT_CHAT_DECISION",
                    ModelTier.FAST,
                    TOOL_DECISION_PROMPT.formatted(serializeAllowedToolDefinitions(allowedToolNames)),
                    decisionMessages,
                    decisionResult,
                    elapsedMs,
                    true
            );
            logLlmCall(context, "tool_decision", decisionMessages.size(), allowedToolNames.size(), hasToolCall, hasToolCall ? "TOOL_CALL" : "FINAL_ANSWER", elapsedMs, decisionResult);
            steps.add(step(context, state, 1, AgentActionType.MODEL_CALL, null, null, true, null, elapsedMs, null));
        } catch (LlmException exception) {
            String errorCode = mapLlmErrorCode(exception);
            log.warn("agent_chat llm_call_failed requestId={} phase=tool_decision errorCode={}", context.getRequestId(), errorCode, exception);
            steps.add(step(context, state, 1, AgentActionType.MODEL_CALL, null, null, false, errorCode, 0, errorCode));
            return chatResponse("模型调用失败，请稍后重试。", false, null, null, context, state, false, null, null, steps, errorCode);
        }
        AgentToolDecision.ToolCall toolCall = decision.getToolCall();
        boolean modelSelectedTool = toolCall != null && StringUtils.hasText(toolCall.getToolName());
        log.info("agent_chat model_decision: requestId={}, selectedTool={}, toolName={}",
                context.getRequestId(),
                modelSelectedTool,
                modelSelectedTool ? toolCall.getToolName() : null);

        if (!modelSelectedTool) {
            steps.add(step(context, state, 2, AgentActionType.FINAL_ANSWER, null, null, true, null, 0, "FINAL_ANSWER"));
            return chatResponse(decision.getAnswer(), false, null, null, context, state, false, null, null, steps, "FINAL_ANSWER");
        }

        String toolName = toolCall.getToolName().trim();
        String argumentsSummary = summarizeArguments(toolCall.getArguments());
        steps.add(step(context, state, 2, AgentActionType.TOOL_CALL, toolName, argumentsSummary, true, null, 0, null));
        if (!allowedToolNames.contains(toolName)) {
            ToolDefinition definition = toolRegistry.findDefinition(toolName).orElse(null);
            AgentToolRolloutDecision visibilityDecision = definition == null ? AgentToolRolloutDecision.allow() : rolloutService.visibilityDecision(toolName, context);
            String errorCode = visibilityDecision.isRolloutBlocked()
                    ? AgentErrorCode.ROLLOUT_BLOCKED.name()
                    : AgentErrorCode.PERMISSION_DENIED.name();
            if (visibilityDecision.isRolloutBlocked()) {
                recordShadowDecision(context, toolName, toolCall.getArguments(), "NOT_VALIDATED", definition.getRiskLevel(), visibilityDecision, 0);
            }
            log.warn("agent_chat blocked_tool: requestId={}, toolName={}, reason=NOT_ALLOWED",
                    context.getRequestId(),
                    toolName);
            ToolResult deniedResult = ToolResult.failure(toolName, errorCode, "current user is not allowed to call this tool", 0);
            steps.add(step(context, state, 3, AgentActionType.STOP, toolName, argumentsSummary, false, errorCode, 0, errorCode));
            AgentChatResponse response = chatResponse(
                    visibilityDecision.isRolloutBlocked() ? "该工具尚未对当前用户开放。" : "当前用户无权调用该工具。",
                    false,
                    toolName,
                    deniedResult,
                    context,
                    state,
                    false,
                    null,
                    null,
                    steps,
                    errorCode
            );
            response.setToolDebugInfo(visibilityDecision.isRolloutBlocked() ? debugInfo(toolName, visibilityDecision) : null);
            return response;
        }

        ToolDefinition definition = toolRegistry.findDefinition(toolName).orElse(null);
        AgentToolRolloutDecision rolloutDecision = rolloutService.evaluate(toolName, context, 1);
        if (rolloutDecision.getPolicyDecision() == ShadowToolPolicyDecision.MAX_CALLS_EXCEEDED || rolloutDecision.isRolloutBlocked()) {
            String errorCode = rolloutDecision.getPolicyDecision() == ShadowToolPolicyDecision.MAX_CALLS_EXCEEDED
                    ? AgentErrorCode.TOOL_MAX_CALLS_EXCEEDED.name()
                    : AgentErrorCode.ROLLOUT_BLOCKED.name();
            recordShadowDecision(context, toolName, toolCall.getArguments(), "NOT_VALIDATED", definition == null ? null : definition.getRiskLevel(), rolloutDecision, 0);
            steps.add(step(context, state, 3, AgentActionType.STOP, toolName, argumentsSummary, false, errorCode, 0, errorCode));
            AgentChatResponse response = chatResponse("该工具当前已被灰度策略拦截。", false, toolName, null, context, state, false, null, null, steps, errorCode);
            response.setToolDebugInfo(debugInfo(toolName, rolloutDecision));
            return response;
        }
        if (rolloutDecision.isShadowOnly()) {
            return shadowOnlyResponse(toolName, toolCall.getArguments(), context, state, steps, argumentsSummary, definition, rolloutDecision);
        }
        if (rolloutDecision.isRequiresConfirmation()) {
            AgentChatResponse response = createConfirmationResponse(toolName, toolCall.getArguments(), context, state, steps, argumentsSummary);
            response.setToolDebugInfo(debugInfo(toolName, rolloutDecision));
            return response;
        }
        if (definition != null && definition.getRiskLevel() == RiskLevel.HIGH) {
            AgentChatResponse response = createConfirmationResponse(toolName, toolCall.getArguments(), context, state, steps, argumentsSummary);
            response.setToolDebugInfo(new AgentToolDebugInfo(toolName, false, false, true, "HIGH_RISK_TOOL"));
            return response;
        }

        long toolStartedAt = System.nanoTime();
        ToolResult toolResult = toolExecutionService.execute(toolName, toolCall.getArguments(), context);
        steps.add(step(context, state, 3, AgentActionType.TOOL_RESULT, toolName, argumentsSummary, toolResult.isSuccess(), toolResult.getErrorCode(), elapsedMillis(toolStartedAt), toolResult.getErrorCode()));
        log.info("agent_chat tool_executed: requestId={}, toolName={}, success={}, elapsedMs={}",
                context.getRequestId(),
                toolName,
                toolResult.isSuccess(),
                toolResult.getElapsedMs());
        boolean toolStateUpdated = conversationStateService.updateAfterToolResult(state, toolResult);
        log.info("agent_chat state_after_tool requestId={}, conversationId={}, userId={}, stateUpdated={}",
                context.getRequestId(),
                state.getConversationId(),
                context.getUserId(),
                toolStateUpdated);

        String finalAnswer;
        try {
            List<LlmMessage> finalMessages = buildFinalAnswerMessages(message, rawDecision, toolResult);
            long finalStartedAt = System.nanoTime();
            costGovernanceService.checkBeforeLlm(
                    context.getTenantId(),
                    context.getUserId(),
                    "AGENT_CHAT_FINAL",
                    FINAL_ANSWER_PROMPT,
                    finalMessages,
                    costGovernanceService.properties().getAgentMaxInputTokens(),
                    llmProperties.getMaxOutputTokens()
            );
            LlmGenerationResult finalResult = llmClient.generateWithUsage(FINAL_ANSWER_PROMPT, finalMessages);
            finalAnswer = finalResult.getAnswer();
            long elapsedMs = elapsedMillis(finalStartedAt);
            costGovernanceService.recordUsage(
                    context.getRequestId(),
                    context.getTenantId(),
                    context.getUserId(),
                    "AGENT_CHAT_FINAL",
                    ModelTier.STANDARD,
                    FINAL_ANSWER_PROMPT,
                    finalMessages,
                    finalResult,
                    elapsedMs,
                    true
            );
            logLlmCall(context, "final_answer", finalMessages.size(), 0, false, "FINAL_ANSWER", elapsedMs, finalResult);
            steps.add(step(context, state, 4, AgentActionType.MODEL_CALL, null, null, true, null, elapsedMs, null));
        } catch (LlmException exception) {
            String errorCode = mapLlmErrorCode(exception);
            log.warn("agent_chat llm_call_failed requestId={} phase=final_answer errorCode={}", context.getRequestId(), errorCode, exception);
            steps.add(step(context, state, 4, AgentActionType.MODEL_CALL, null, null, false, errorCode, 0, errorCode));
            return chatResponse("工具已执行，但模型总结失败。", true, toolName, toolResult, context, state, false, null, null, steps, errorCode);
        }

        steps.add(step(context, state, 5, AgentActionType.FINAL_ANSWER, null, null, true, null, 0, "FINAL_ANSWER"));
        return chatResponse(finalAnswer, true, toolName, toolResult, context, state, false, null, null, steps, "FINAL_ANSWER");
    }

    private AgentChatResponse shadowOnlyResponse(
            String toolName,
            JsonNode arguments,
            ToolExecutionContext context,
            ConversationState state,
            List<AgentStep> steps,
            String argumentsSummary,
            ToolDefinition definition,
            AgentToolRolloutDecision rolloutDecision
    ) {
        try {
            toolExecutionService.validate(toolName, arguments, context);
            recordShadowDecision(context, toolName, arguments, "OK", definition == null ? null : definition.getRiskLevel(), rolloutDecision, 0);
            steps.add(step(context, state, 3, AgentActionType.STOP, toolName, argumentsSummary, true, AgentErrorCode.TOOL_SHADOWED.name(), 0, AgentErrorCode.TOOL_SHADOWED.name()));
            AgentChatResponse response = chatResponse(
                    "工具处于 Shadow Mode，已完成校验并记录决策，但不会实际执行。",
                    false,
                    toolName,
                    null,
                    context,
                    state,
                    false,
                    null,
                    null,
                    steps,
                    AgentErrorCode.TOOL_SHADOWED.name()
            );
            response.setToolDebugInfo(debugInfo(toolName, rolloutDecision));
            return response;
        } catch (ToolException exception) {
            String errorCode = StringUtils.hasText(exception.getErrorType()) ? exception.getErrorType() : AgentErrorCode.VALIDATION_ERROR.name();
            recordShadowDecision(context, toolName, arguments, "FAILED:" + errorCode, definition == null ? null : definition.getRiskLevel(), rolloutDecision, 0);
            ToolResult result = ToolResult.failure(toolName, errorCode, exception.getMessage(), 0);
            steps.add(step(context, state, 3, AgentActionType.STOP, toolName, argumentsSummary, false, errorCode, 0, errorCode));
            AgentChatResponse response = chatResponse("Shadow Mode 工具参数或权限校验失败。", false, toolName, result, context, state, false, null, null, steps, errorCode);
            response.setToolDebugInfo(debugInfo(toolName, rolloutDecision));
            return response;
        }
    }

    public ToolExecutionContext mockContext() {
        return new ToolExecutionContext(
                RequestContext.requestIdOr(UUID.randomUUID().toString()),
                "user_001",
                "default-department",
                1
        );
    }

    private AgentChatResponse createConfirmationResponse(
            String toolName,
            JsonNode arguments,
            ToolExecutionContext context,
            ConversationState state,
            List<AgentStep> steps,
            String argumentsSummary
    ) {
        try {
            ValidatedToolCall validatedToolCall = toolExecutionService.validate(toolName, arguments, context);
            String summary = buildConfirmationSummary(toolName, arguments);
            PendingConfirmation pending = confirmationService.createPendingConfirmation(validatedToolCall, context, summary);
            conversationStateService.updatePendingConfirmation(state, pending.getConfirmationId());
            String confirmationMessage = "该操作风险较高，需要您确认后才会执行：" + summary;
            steps.add(step(context, state, 3, AgentActionType.STOP, toolName, argumentsSummary, true, AgentErrorCode.CONFIRMATION_REQUIRED.name(), 0, AgentErrorCode.CONFIRMATION_REQUIRED.name()));

            log.warn("agent_chat high_risk_confirmation_required requestId={}, confirmationId={}, userId={}, toolName={}, riskLevel={}, status={}",
                    context.getRequestId(),
                    pending.getConfirmationId(),
                    context.getUserId(),
                    pending.getToolName(),
                    pending.getRiskLevel(),
                    pending.getStatus());

            return chatResponse(
                    confirmationMessage,
                    false,
                    toolName,
                    null,
                    context,
                    state,
                    true,
                    pending.getConfirmationId(),
                    confirmationMessage,
                    steps,
                    AgentErrorCode.CONFIRMATION_REQUIRED.name()
            );
        } catch (ToolException exception) {
            ToolResult result = ToolResult.failure(toolName, exception.getErrorType(), exception.getMessage(), 0);
            String errorCode = StringUtils.hasText(exception.getErrorType()) ? exception.getErrorType() : AgentErrorCode.VALIDATION_ERROR.name();
            steps.add(step(context, state, 3, AgentActionType.STOP, toolName, argumentsSummary, false, errorCode, 0, errorCode));
            return chatResponse("无法创建确认：" + exception.getMessage(), false, toolName, result, context, state, false, null, null, steps, errorCode);
        }
    }

    private String buildConfirmationSummary(String toolName, JsonNode arguments) {
        String orderId = arguments == null ? null : arguments.path("orderId").asText(null);
        if ("cancel_order".equals(toolName) && StringUtils.hasText(orderId)) {
            return "取消订单 " + orderId;
        }
        return "执行高风险工具 " + toolName;
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
        toolMessage.put("type", "tool_result");
        toolMessage.put("toolName", toolResult.getToolName());
        toolMessage.put("success", toolResult.isSuccess());
        toolMessage.put("result", compactToolResultForModel(toolResult));
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

    private Object compactToolResultForModel(ToolResult toolResult) {
        if (toolResult == null || !(toolResult.getResult() instanceof SearchKnowledgeToolResult knowledgeResult)) {
            return toolResult == null ? null : toolResult.getResult();
        }

        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("answerable", knowledgeResult.isAnswerable());
        compact.put("retrievedCount", knowledgeResult.getRetrievedCount());
        compact.put("sources", knowledgeResult.getSources());
        compact.put("contexts", knowledgeResult.getContexts().stream()
                .map(context -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("content", limitText(context.getContent(), 260));
                    item.put("sourceId", context.getSourceId());
                    item.put("documentId", context.getDocumentId());
                    item.put("title", context.getTitle());
                    item.put("score", context.getScore());
                    return item;
                })
                .toList());
        return compact;
    }

    private static String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private AgentChatResponse chatResponse(
            String answer,
            boolean toolCalled,
            String toolName,
            ToolResult toolResult,
            ToolExecutionContext context,
            ConversationState state,
            boolean requiresConfirmation,
            String confirmationId,
            String confirmationMessage,
            List<AgentStep> steps,
            String stopReason
    ) {
        return new AgentChatResponse(
                sanitizeAnswerForUser(answer),
                toolCalled,
                toolName,
                toolResult,
                context.getRequestId(),
                state.getConversationId(),
                requiresConfirmation,
                confirmationId,
                confirmationMessage,
                steps,
                stopReason
        );
    }

    private AgentToolDebugInfo debugInfo(String toolName, AgentToolRolloutDecision decision) {
        return new AgentToolDebugInfo(
                toolName,
                decision.isShadowOnly(),
                decision.isRolloutBlocked(),
                decision.isRequiresConfirmation(),
                decision.getBlockedReason()
        );
    }

    private void recordShadowDecision(
            ToolExecutionContext context,
            String toolName,
            JsonNode arguments,
            String validationResult,
            RiskLevel riskLevel,
            AgentToolRolloutDecision rolloutDecision,
            long latencyMs
    ) {
        shadowDecisionService.record(
                context,
                toolName,
                arguments,
                validationResult,
                riskLevel,
                rolloutDecision.getPolicyDecision(),
                rolloutDecision.getBlockedReason(),
                llmProperties.getModel(),
                latencyMs
        );
        log.info("agent_tool_rollout_decision requestId={} toolName={} policyDecision={} validationResult={} blockedReason={}",
                context.getRequestId(),
                toolName,
                rolloutDecision.getPolicyDecision(),
                validationResult,
                rolloutDecision.getBlockedReason());
    }

    private static String sanitizeAnswerForUser(String answer) {
        if (!StringUtils.hasText(answer)) {
            return answer;
        }
        if (containsPromptLeak(answer)) {
            return "抱歉，当前回答包含不可展示的内部指令内容，请换一种问法重试。";
        }
        return answer;
    }

    private static boolean containsPromptLeak(String answer) {
        return answer.contains("你是一个企业客服 Agent")
                || answer.contains("工具选择规则")
                || answer.contains("后端当前暴露给你的工具")
                || answer.contains("你必须只返回 JSON")
                || answer.contains("system prompt");
    }

    private AgentStep step(
            ToolExecutionContext context,
            ConversationState state,
            int stepIndex,
            AgentActionType actionType,
            String toolName,
            String argumentsSummary,
            boolean success,
            String errorCode,
            long elapsedMs,
            String stopReason
    ) {
        AgentStep step = new AgentStep(
                context.getRequestId(),
                state.getConversationId(),
                stepIndex,
                actionType,
                toolName,
                argumentsSummary,
                success,
                errorCode,
                elapsedMs,
                stopReason
        );
        ToolDefinition definition = StringUtils.hasText(toolName) ? toolRegistry.findDefinition(toolName).orElse(null) : null;
        boolean requiresConfirmation = AgentErrorCode.CONFIRMATION_REQUIRED.name().equals(errorCode)
                || AgentErrorCode.CONFIRMATION_REQUIRED.name().equals(stopReason);
        log.info("agent_step requestId={} conversationId={} stepIndex={} actionType={} toolName={} riskLevel={} latencyMs={} success={} errorCode={} requiresConfirmation={} stopReason={}",
                step.getRequestId(),
                step.getConversationId(),
                step.getStepIndex(),
                step.getActionType(),
                step.getToolName(),
                definition == null ? null : definition.getRiskLevel(),
                step.getElapsedMs(),
                step.isSuccess(),
                step.getErrorCode(),
                requiresConfirmation,
                step.getStopReason());
        return step;
    }

    private void logLlmCall(
            ToolExecutionContext context,
            String phase,
            int messageCount,
            int toolsCount,
            boolean hasToolCall,
            String finishReason,
            long elapsedMs,
            LlmGenerationResult result
    ) {
        log.info("agent_llm_call requestId={} phase={} model={} temperature={} maxTokens={} messageCount={} toolsCount={} hasToolCall={} finishReason={} elapsedMs={} promptTokens={} completionTokens={} totalTokens={}",
                context.getRequestId(),
                phase,
                llmProperties.getModel(),
                llmProperties.getTemperature(),
                llmProperties.getMaxOutputTokens(),
                messageCount,
                toolsCount,
                hasToolCall,
                finishReason,
                elapsedMs,
                result.getPromptTokens(),
                result.getCompletionTokens(),
                result.getTotalTokens());
    }

    private String mapLlmErrorCode(LlmException exception) {
        if (exception.getErrorType() == LlmErrorType.INVALID_STRUCTURED_OUTPUT) {
            return AgentErrorCode.LLM_OUTPUT_INVALID.name();
        }
        return AgentErrorCode.LLM_CALL_FAILED.name();
    }

    private String summarizeArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return "keys=[]";
        }
        String orderId = arguments.path("orderId").asText(null);
        if (StringUtils.hasText(orderId)) {
            return "orderId=" + orderId;
        }
        List<String> keys = new ArrayList<>();
        arguments.fieldNames().forEachRemaining(keys::add);
        return "keys=" + keys;
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static CostGovernanceService defaultCostGovernanceService(LlmProperties llmProperties) {
        return new CostGovernanceService(
                new CostProperties(),
                new TokenEstimator(),
                new QuotaService(),
                new RateLimitService(),
                new UsageRecordService(),
                llmProperties
        );
    }
}
