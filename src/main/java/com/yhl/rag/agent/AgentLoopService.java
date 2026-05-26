package com.yhl.rag.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.cost.CostException;
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
import com.yhl.rag.observability.MetricsService;
import com.yhl.rag.tool.RiskLevel;
import com.yhl.rag.tool.ToolDefinition;
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
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);

    private static final String LOOP_PROMPT = """
            你是一个受控企业客服 Agent。你不能直接执行工具，只能请求后端调用工具。

            当前用户可用工具如下：
            %s

            规则：
            - 如果你已经能回答用户，返回 final answer。
            - 如果需要工具，最多一次只请求一个 toolCall。
            - 查询订单使用 query_order。
            - 取消订单使用 cancel_order，但这是高风险工具，后端会要求用户确认。
            - 查询企业知识库、制度文档、产品文档或内部资料使用 search_knowledge_base。
            - search_knowledge_base 不能用于查询订单、用户隐私或实时业务数据。
            - 不要把 userId、tenantId、topK、scoreThreshold 放入工具参数，真实用户身份和检索范围由后端提供。
            - 不要重复请求相同 toolName 和相同 arguments。

            你必须只返回 JSON，不要返回 markdown，不要解释。
            返回格式只能是以下二选一：
            {"answer":"最终回复用户的话","toolCall":null}
            {"answer":null,"toolCall":{"toolName":"工具名","arguments":{"orderId":"订单号"}}}
            """;

    private final LlmClient llmClient;

    private final LlmProperties llmProperties;

    private final ToolRegistry toolRegistry;

    private final ToolExecutionService toolExecutionService;

    private final AllowedToolService allowedToolService;

    private final AgentToolRolloutService rolloutService;

    private final ShadowToolDecisionService shadowDecisionService;

    private final ConfirmationService confirmationService;

    private final AgentLoopConfig config;

    private final ConversationStateService conversationStateService;

    private final AgentContextBuilder agentContextBuilder;

    private final ObjectMapper objectMapper;

    private final CostGovernanceService costGovernanceService;

    private final MetricsService metricsService;

    @Autowired
    public AgentLoopService(
            LlmClient llmClient,
            LlmProperties llmProperties,
            ToolRegistry toolRegistry,
            ToolExecutionService toolExecutionService,
            AllowedToolService allowedToolService,
            AgentToolRolloutService rolloutService,
            ShadowToolDecisionService shadowDecisionService,
            ConfirmationService confirmationService,
            AgentLoopConfig config,
            ConversationStateService conversationStateService,
            AgentContextBuilder agentContextBuilder,
            ObjectMapper objectMapper,
            CostGovernanceService costGovernanceService
    ) {
        this(
                llmClient,
                llmProperties,
                toolRegistry,
                toolExecutionService,
                allowedToolService,
                rolloutService,
                shadowDecisionService,
                confirmationService,
                config,
                conversationStateService,
                agentContextBuilder,
                objectMapper,
                costGovernanceService,
                new MetricsService()
        );
    }

    public AgentLoopService(
            LlmClient llmClient,
            LlmProperties llmProperties,
            ToolRegistry toolRegistry,
            ToolExecutionService toolExecutionService,
            AllowedToolService allowedToolService,
            ConfirmationService confirmationService,
            AgentLoopConfig config,
            ConversationStateService conversationStateService,
            AgentContextBuilder agentContextBuilder,
            ObjectMapper objectMapper,
            CostGovernanceService costGovernanceService
    ) {
        this(
                llmClient,
                llmProperties,
                toolRegistry,
                toolExecutionService,
                allowedToolService,
                confirmationService,
                config,
                conversationStateService,
                agentContextBuilder,
                objectMapper,
                costGovernanceService,
                new MetricsService()
        );
    }

    public AgentLoopService(
            LlmClient llmClient,
            LlmProperties llmProperties,
            ToolRegistry toolRegistry,
            ToolExecutionService toolExecutionService,
            AllowedToolService allowedToolService,
            ConfirmationService confirmationService,
            AgentLoopConfig config,
            ConversationStateService conversationStateService,
            AgentContextBuilder agentContextBuilder,
            ObjectMapper objectMapper,
            CostGovernanceService costGovernanceService,
            MetricsService metricsService
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
                config,
                conversationStateService,
                agentContextBuilder,
                objectMapper,
                costGovernanceService,
                metricsService
        );
    }

    public AgentLoopService(
            LlmClient llmClient,
            LlmProperties llmProperties,
            ToolRegistry toolRegistry,
            ToolExecutionService toolExecutionService,
            AllowedToolService allowedToolService,
            AgentToolRolloutService rolloutService,
            ShadowToolDecisionService shadowDecisionService,
            ConfirmationService confirmationService,
            AgentLoopConfig config,
            ConversationStateService conversationStateService,
            AgentContextBuilder agentContextBuilder,
            ObjectMapper objectMapper,
            CostGovernanceService costGovernanceService,
            MetricsService metricsService
    ) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.allowedToolService = allowedToolService;
        this.rolloutService = rolloutService;
        this.shadowDecisionService = shadowDecisionService;
        this.confirmationService = confirmationService;
        this.config = config;
        this.conversationStateService = conversationStateService;
        this.agentContextBuilder = agentContextBuilder;
        this.objectMapper = objectMapper;
        this.costGovernanceService = costGovernanceService;
        this.metricsService = metricsService;
    }

    public AgentLoopService(
            LlmClient llmClient,
            LlmProperties llmProperties,
            ToolRegistry toolRegistry,
            ToolExecutionService toolExecutionService,
            AllowedToolService allowedToolService,
            ConfirmationService confirmationService,
            AgentLoopConfig config,
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
                confirmationService,
                config,
                conversationStateService,
                agentContextBuilder,
                objectMapper,
                defaultCostGovernanceService(llmProperties),
                new MetricsService()
        );
    }

    public AgentLoopResponse run(String conversationId, String message, ToolExecutionContext context) {
        long loopStartedAt = System.nanoTime();
        List<AgentStep> steps = new ArrayList<>();
        List<ToolResult> toolResults = new ArrayList<>();
        Map<String, Integer> repeatedToolCalls = new HashMap<>();
        int llmCallCount = 0;
        int maxSteps = Math.min(config.getMaxSteps(), costGovernanceService.properties().getAgentMaxSteps());
        int maxLlmCalls = costGovernanceService.properties().getAgentMaxLlmCalls();
        Map<String, Integer> toolCallCounts = new HashMap<>();

        ConversationState state = conversationStateService.getOrCreate(conversationId, context.getUserId());
        boolean stateUpdated = conversationStateService.updateAfterUserMessage(state, message);
        List<String> allowedToolNames = allowedToolService.allowedToolNames(context);
        List<LlmMessage> messages = new ArrayList<>(
                agentContextBuilder.buildMessages(context.getUserId(), state.getConversationId(), message, allowedToolNames)
        );

        log.info("agent_loop started requestId={}, conversationId={}, userId={}, stateUpdated={}",
                context.getRequestId(),
                state.getConversationId(),
                context.getUserId(),
                stateUpdated);

        for (int stepIndex = 1; stepIndex <= maxSteps; stepIndex++) {
            if (elapsedMillis(loopStartedAt) > config.getMaxDurationMs()) {
                return stop("已达到 Agent 最大执行时长。", AgentErrorCode.AGENT_TIMEOUT.name(), steps, toolResults, context, state, null, null, 0);
            }
            if (llmCallCount >= maxLlmCalls) {
                return stop("已达到 Agent 最大模型调用次数。", AgentErrorCode.AGENT_MAX_LLM_CALLS_EXCEEDED.name(), steps, toolResults, context, state, null, null, System.nanoTime());
            }

            long stepStartedAt = System.nanoTime();
            String rawDecision;
            AgentToolDecision decision;
            try {
                String loopPrompt = LOOP_PROMPT.formatted(serializeAllowedToolDefinitions(allowedToolNames));
                costGovernanceService.checkBeforeLlm(
                        context.getTenantId(),
                        context.getUserId(),
                        "AGENT_LOOP",
                        loopPrompt,
                        messages,
                        costGovernanceService.properties().getChatMaxInputTokens(),
                        llmProperties.getMaxOutputTokens()
                );
                LlmGenerationResult decisionResult = llmClient.generateWithUsage(
                        loopPrompt,
                        messages
                );
                llmCallCount++;
                rawDecision = decisionResult.getAnswer();
                decision = parseDecision(rawDecision);
                boolean hasToolCall = decision.getToolCall() != null && StringUtils.hasText(decision.getToolCall().getToolName());
                long modelElapsedMs = elapsedMillis(stepStartedAt);
                costGovernanceService.recordUsage(
                        context.getRequestId(),
                        context.getTenantId(),
                        context.getUserId(),
                        "AGENT_LOOP",
                        ModelTier.ADVANCED,
                        loopPrompt,
                        messages,
                        decisionResult,
                        modelElapsedMs,
                        true
                );
                logLlmCall(context, messages.size(), allowedToolNames.size(), hasToolCall, hasToolCall ? "TOOL_CALL" : "FINAL_ANSWER", modelElapsedMs, decisionResult);
                AgentStep modelStep = step(context, state, steps.size() + 1, AgentActionType.MODEL_CALL, null, null, true, null, modelElapsedMs, null);
                steps.add(modelStep);
                logStep(modelStep);
            } catch (CostException exception) {
                String errorCode = exception.getErrorCode().name();
                AgentStep modelStep = step(context, state, steps.size() + 1, AgentActionType.MODEL_CALL, null, null, false, errorCode, elapsedMillis(stepStartedAt), errorCode);
                steps.add(modelStep);
                logStep(modelStep);
                return finish(exception.getMessage(), errorCode, steps, toolResults, context, state);
            } catch (LlmException exception) {
                String errorCode = mapLlmErrorCode(exception);
                AgentStep modelStep = step(context, state, steps.size() + 1, AgentActionType.MODEL_CALL, null, null, false, errorCode, elapsedMillis(stepStartedAt), errorCode);
                steps.add(modelStep);
                logStep(modelStep);
                log.warn("agent_loop llm_call_failed requestId={} errorCode={}", context.getRequestId(), errorCode, exception);
                return finish("模型调用失败，请稍后重试。", errorCode, steps, toolResults, context, state);
            }
            AgentToolDecision.ToolCall toolCall = decision.getToolCall();
            if (toolCall == null || !StringUtils.hasText(toolCall.getToolName())) {
                AgentStep step = step(context, state, steps.size() + 1, AgentActionType.FINAL_ANSWER, null, null, true, null, 0, "FINAL_ANSWER");
                steps.add(step);
                logStep(step);
                return finish(decision.getAnswer(), "FINAL_ANSWER", steps, toolResults, context, state);
            }

            String toolName = toolCall.getToolName().trim();
            String argumentsSummary = summarizeArguments(toolCall.getArguments());
            ToolDefinition definition = toolRegistry.findDefinition(toolName).orElse(null);
            if (definition == null) {
                AgentStep step = stopStep(context, state, steps.size() + 1, toolName, argumentsSummary, AgentErrorCode.UNKNOWN_TOOL.name(), stepStartedAt);
                steps.add(step);
                logStep(step);
                return finish("该工具不存在，已停止执行。", AgentErrorCode.UNKNOWN_TOOL.name(), steps, toolResults, context, state);
            }

            if (!allowedToolNames.contains(toolName)) {
                AgentToolRolloutDecision visibilityDecision = rolloutService.visibilityDecision(toolName, context);
                String errorCode = visibilityDecision.isRolloutBlocked()
                        ? AgentErrorCode.ROLLOUT_BLOCKED.name()
                        : AgentErrorCode.PERMISSION_DENIED.name();
                if (visibilityDecision.isRolloutBlocked()) {
                    recordShadowDecision(context, toolName, toolCall.getArguments(), "NOT_VALIDATED", definition.getRiskLevel(), visibilityDecision, stepStartedAt);
                }
                AgentStep step = stopStep(context, state, steps.size() + 1, toolName, argumentsSummary, errorCode, stepStartedAt);
                steps.add(step);
                logStep(step);
                return finishWithDebug(
                        visibilityDecision.isRolloutBlocked() ? "该工具尚未对当前用户开放。" : "当前用户无权调用该工具。",
                        errorCode,
                        steps,
                        toolResults,
                        context,
                        state,
                        visibilityDecision.isRolloutBlocked() ? debugInfo(toolName, visibilityDecision) : null
                );
            }

            String callSignature = toolName + ":" + canonicalArguments(toolCall.getArguments());
            int currentCount = repeatedToolCalls.getOrDefault(callSignature, 0);
            if (currentCount >= config.getMaxRepeatedToolCalls()) {
                AgentStep step = stopStep(context, state, steps.size() + 1, toolName, argumentsSummary, AgentErrorCode.DUPLICATE_TOOL_CALL.name(), stepStartedAt);
                steps.add(step);
                logStep(step);
                return finish("检测到重复工具调用，已停止执行。", AgentErrorCode.DUPLICATE_TOOL_CALL.name(), steps, toolResults, context, state);
            }
            repeatedToolCalls.put(callSignature, currentCount + 1);

            int callsInRequest = toolCallCounts.getOrDefault(toolName, 0) + 1;
            AgentToolRolloutDecision rolloutDecision = rolloutService.evaluate(toolName, context, callsInRequest);
            toolCallCounts.put(toolName, callsInRequest);
            if (rolloutDecision.getPolicyDecision() == ShadowToolPolicyDecision.MAX_CALLS_EXCEEDED) {
                recordShadowDecision(context, toolName, toolCall.getArguments(), "NOT_VALIDATED", definition.getRiskLevel(), rolloutDecision, stepStartedAt);
                AgentStep step = stopStep(context, state, steps.size() + 1, toolName, argumentsSummary, AgentErrorCode.TOOL_MAX_CALLS_EXCEEDED.name(), stepStartedAt);
                steps.add(step);
                logStep(step);
                return finishWithDebug("该工具本次请求调用次数已达到灰度策略上限。", AgentErrorCode.TOOL_MAX_CALLS_EXCEEDED.name(), steps, toolResults, context, state, debugInfo(toolName, rolloutDecision));
            }
            if (rolloutDecision.isRolloutBlocked()) {
                recordShadowDecision(context, toolName, toolCall.getArguments(), "NOT_VALIDATED", definition.getRiskLevel(), rolloutDecision, stepStartedAt);
                AgentStep step = stopStep(context, state, steps.size() + 1, toolName, argumentsSummary, AgentErrorCode.ROLLOUT_BLOCKED.name(), stepStartedAt);
                steps.add(step);
                logStep(step);
                return finishWithDebug("该工具当前已被灰度策略拦截。", AgentErrorCode.ROLLOUT_BLOCKED.name(), steps, toolResults, context, state, debugInfo(toolName, rolloutDecision));
            }
            if (rolloutDecision.isShadowOnly()) {
                return shadowOnlyStop(stepStartedAt, toolName, argumentsSummary, toolCall.getArguments(), definition, steps, toolResults, context, state, rolloutDecision);
            }
            if (rolloutDecision.isRequiresConfirmation()) {
                AgentLoopResponse response = highRiskConfirmation(stepIndex, stepStartedAt, toolName, argumentsSummary, toolCall.getArguments(), definition, steps, toolResults, context, state);
                response.setToolDebugInfo(debugInfo(toolName, rolloutDecision));
                return response;
            }

            if (definition.getRiskLevel() == RiskLevel.HIGH && !config.isAllowHighRiskTools()) {
                AgentLoopResponse response = highRiskConfirmation(stepIndex, stepStartedAt, toolName, argumentsSummary, toolCall.getArguments(), definition, steps, toolResults, context, state);
                response.setToolDebugInfo(new AgentToolDebugInfo(toolName, false, false, true, "HIGH_RISK_TOOL"));
                return response;
            }

            if (elapsedMillis(loopStartedAt) > config.getMaxDurationMs()) {
                return stop("已达到 Agent 最大执行时长。", AgentErrorCode.AGENT_TIMEOUT.name(), steps, toolResults, context, state, toolName, argumentsSummary, stepStartedAt);
            }

            AgentStep toolCallStep = step(context, state, steps.size() + 1, AgentActionType.TOOL_CALL, toolName, argumentsSummary, true, null, 0, null);
            steps.add(toolCallStep);
            logStep(toolCallStep);
            ToolResult toolResult = toolExecutionService.execute(toolName, toolCall.getArguments(), context);
            toolResults.add(toolResult);
            AgentStep step = step(
                    context,
                    state,
                    steps.size() + 1,
                    AgentActionType.TOOL_RESULT,
                    toolName,
                    argumentsSummary,
                    toolResult.isSuccess(),
                    toolResult.getErrorCode(),
                    toolResult.getElapsedMs(),
                    toolResult.getErrorCode()
            );
            steps.add(step);
            logStep(step);
            boolean toolStateUpdated = conversationStateService.updateAfterToolResult(state, toolResult);
            log.info("agent_loop state_after_tool requestId={}, conversationId={}, userId={}, stateUpdated={}",
                    context.getRequestId(),
                    state.getConversationId(),
                    context.getUserId(),
                    toolStateUpdated);

            messages.add(new LlmMessage("assistant", cleanJson(rawDecision)));
            messages.add(new LlmMessage("user", "tool message:\n" + serializeToolResult(toolResult)));

            if (!toolResult.isSuccess()) {
                String stopReason = StringUtils.hasText(toolResult.getErrorCode())
                        ? toolResult.getErrorCode()
                        : "TOOL_EXECUTION_FAILED";
                return finish("工具执行失败：" + toolResult.getErrorMessage(), stopReason, steps, toolResults, context, state);
            }
        }

        metricsService.recordAgentMaxSteps();
        return finish("已达到 Agent 最大执行步数。", AgentErrorCode.AGENT_MAX_STEPS_EXCEEDED.name(), steps, toolResults, context, state);
    }

    private AgentLoopResponse shadowOnlyStop(
            long stepStartedAt,
            String toolName,
            String argumentsSummary,
            JsonNode arguments,
            ToolDefinition definition,
            List<AgentStep> steps,
            List<ToolResult> toolResults,
            ToolExecutionContext context,
            ConversationState state,
            AgentToolRolloutDecision rolloutDecision
    ) {
        try {
            toolExecutionService.validate(toolName, arguments, context);
            recordShadowDecision(context, toolName, arguments, "OK", definition.getRiskLevel(), rolloutDecision, stepStartedAt);
            AgentStep step = step(context, state, steps.size() + 1, AgentActionType.STOP, toolName, argumentsSummary, true, AgentErrorCode.TOOL_SHADOWED.name(), elapsedMillis(stepStartedAt), AgentErrorCode.TOOL_SHADOWED.name());
            steps.add(step);
            logStep(step);
            return finishWithDebug("工具处于 Shadow Mode，已完成校验并记录决策，但不会实际执行。", AgentErrorCode.TOOL_SHADOWED.name(), steps, toolResults, context, state, debugInfo(toolName, rolloutDecision));
        } catch (ToolException exception) {
            String errorCode = StringUtils.hasText(exception.getErrorType()) ? exception.getErrorType() : AgentErrorCode.VALIDATION_ERROR.name();
            recordShadowDecision(context, toolName, arguments, "FAILED:" + errorCode, definition.getRiskLevel(), rolloutDecision, stepStartedAt);
            AgentStep step = stopStep(context, state, steps.size() + 1, toolName, argumentsSummary, errorCode, stepStartedAt);
            steps.add(step);
            logStep(step);
            return finishWithDebug("Shadow Mode 工具参数或权限校验失败。", errorCode, steps, toolResults, context, state, debugInfo(toolName, rolloutDecision));
        }
    }

    private AgentLoopResponse highRiskConfirmation(
            int stepIndex,
            long stepStartedAt,
            String toolName,
            String argumentsSummary,
            JsonNode arguments,
            ToolDefinition definition,
            List<AgentStep> steps,
            List<ToolResult> toolResults,
            ToolExecutionContext context,
            ConversationState state
    ) {
        try {
            ValidatedToolCall validatedToolCall = toolExecutionService.validate(toolName, arguments, context);
            PendingConfirmation pending = confirmationService.createPendingConfirmation(
                    validatedToolCall,
                    context,
                    buildConfirmationSummary(toolName, arguments)
            );
            conversationStateService.updatePendingConfirmation(state, pending.getConfirmationId());
            AgentStep step = step(context, state, steps.size() + 1, AgentActionType.STOP, toolName, argumentsSummary, true, AgentErrorCode.CONFIRMATION_REQUIRED.name(), elapsedMillis(stepStartedAt), AgentErrorCode.CONFIRMATION_REQUIRED.name());
            steps.add(step);
            logStep(step);
            log.info("agent_loop stopped requestId={} stopReason={} confirmationId={} toolName={} riskLevel={}",
                    context.getRequestId(),
                    AgentErrorCode.CONFIRMATION_REQUIRED.name(),
                    pending.getConfirmationId(),
                    toolName,
                    definition.getRiskLevel());
            return new AgentLoopResponse(
                    "该操作风险较高，需要您确认后才会执行：" + pending.getSummary(),
                    false,
                    AgentErrorCode.CONFIRMATION_REQUIRED.name(),
                    steps,
                    toolResults,
                    true,
                    pending.getConfirmationId(),
                    context.getRequestId(),
                    state.getConversationId()
            );
        } catch (RuntimeException exception) {
            AgentStep step = stopStep(context, state, steps.size() + 1, toolName, argumentsSummary, AgentErrorCode.VALIDATION_ERROR.name(), stepStartedAt);
            steps.add(step);
            logStep(step);
            return finish("无法创建确认，工具参数无效或权限不足。", AgentErrorCode.VALIDATION_ERROR.name(), steps, toolResults, context, state);
        }
    }

    private AgentLoopResponse stop(
            String answer,
            String stopReason,
            List<AgentStep> steps,
            List<ToolResult> toolResults,
            ToolExecutionContext context,
            ConversationState state,
            String toolName,
            String argumentsSummary,
            long stepStartedAt
    ) {
        AgentStep step = step(context, state, steps.size() + 1, AgentActionType.STOP, toolName, argumentsSummary, false, stopReason, elapsedMillis(stepStartedAt), stopReason);
        steps.add(step);
        logStep(step);
        return finish(answer, stopReason, steps, toolResults, context, state);
    }

    private AgentStep stopStep(ToolExecutionContext context, ConversationState state, int stepIndex, String toolName, String argumentsSummary, String errorCode, long stepStartedAt) {
        return step(context, state, stepIndex, AgentActionType.STOP, toolName, argumentsSummary, false, errorCode, elapsedMillis(stepStartedAt), errorCode);
    }

    private AgentLoopResponse finish(String answer, String stopReason, List<AgentStep> steps, List<ToolResult> toolResults, ToolExecutionContext context, ConversationState state) {
        return finishWithDebug(answer, stopReason, steps, toolResults, context, state, null);
    }

    private AgentLoopResponse finishWithDebug(
            String answer,
            String stopReason,
            List<AgentStep> steps,
            List<ToolResult> toolResults,
            ToolExecutionContext context,
            ConversationState state,
            AgentToolDebugInfo toolDebugInfo
    ) {
        log.info("agent_loop finished requestId={} conversationId={} userId={} stopReason={} steps={}",
                context.getRequestId(),
                state.getConversationId(),
                context.getUserId(),
                stopReason,
                steps.size());
        return new AgentLoopResponse(sanitizeAnswerForUser(answer), true, stopReason, steps, toolResults, false, null, context.getRequestId(), state.getConversationId(), toolDebugInfo);
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
            long stepStartedAt
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
                elapsedMillis(stepStartedAt)
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
        if (answer.contains("你是一个企业客服 Agent")
                || answer.contains("工具选择规则")
                || answer.contains("当前用户可用工具")
                || answer.contains("你必须只返回 JSON")
                || answer.contains("system prompt")) {
            return "抱歉，当前回答包含不可展示的内部指令内容，请换一种问法重试。";
        }
        return answer;
    }

    private void logStep(AgentStep step) {
        ToolDefinition definition = StringUtils.hasText(step.getToolName()) ? toolRegistry.findDefinition(step.getToolName()).orElse(null) : null;
        boolean requiresConfirmation = AgentErrorCode.CONFIRMATION_REQUIRED.name().equals(step.getErrorCode())
                || AgentErrorCode.CONFIRMATION_REQUIRED.name().equals(step.getStopReason());
        log.info("agent_loop_step requestId={} conversationId={} stepIndex={} actionType={} toolName={} riskLevel={} latencyMs={} success={} errorCode={} requiresConfirmation={} stopReason={}",
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
    }

    private String serializeAllowedToolDefinitions(List<String> allowedToolNames) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (String toolName : allowedToolNames) {
            toolRegistry.findDefinition(toolName).ifPresent(definitions::add);
        }
        try {
            return objectMapper.writeValueAsString(definitions);
        } catch (JsonProcessingException exception) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "工具定义序列化失败", exception);
        }
    }

    private AgentToolDecision parseDecision(String rawDecision) {
        JsonNode root;
        try {
            root = objectMapper.readTree(cleanJson(rawDecision));
        } catch (JsonProcessingException exception) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "Agent Loop 工具选择结果不是合法 JSON", exception);
        }

        JsonNode toolCalls = root.get("toolCalls");
        if (toolCalls != null && toolCalls.isArray() && toolCalls.size() > 1) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "Agent Loop 每步最多只能选择一个工具");
        }
        if (toolCalls != null && toolCalls.isArray() && toolCalls.size() == 1 && root.get("toolCall") == null) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).set("toolCall", toolCalls.get(0));
        }

        try {
            return objectMapper.treeToValue(root, AgentToolDecision.class);
        } catch (JsonProcessingException exception) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "Agent Loop 工具选择结果结构不合法", exception);
        }
    }

    private String serializeToolResult(ToolResult toolResult) {
        Map<String, Object> toolMessage = new LinkedHashMap<>();
        toolMessage.put("type", "tool_result");
        toolMessage.put("toolName", toolResult.getToolName());
        toolMessage.put("success", toolResult.isSuccess());
        toolMessage.put("result", compactToolResultForModel(toolResult));
        toolMessage.put("errorCode", toolResult.getErrorCode());
        toolMessage.put("errorMessage", toolResult.getErrorMessage());
        try {
            return objectMapper.writeValueAsString(toolMessage);
        } catch (JsonProcessingException exception) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "工具结果序列化失败", exception);
        }
    }

    private String canonicalArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            return arguments.toString();
        }
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

    private String buildConfirmationSummary(String toolName, JsonNode arguments) {
        String orderId = arguments == null ? null : arguments.path("orderId").asText(null);
        if ("cancel_order".equals(toolName) && StringUtils.hasText(orderId)) {
            return "取消订单 " + orderId;
        }
        return "执行高风险工具 " + toolName;
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
        return new AgentStep(
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
    }

    private void logLlmCall(
            ToolExecutionContext context,
            int messageCount,
            int toolsCount,
            boolean hasToolCall,
            String finishReason,
            long elapsedMs,
            LlmGenerationResult result
    ) {
        log.info("agent_llm_call requestId={} model={} temperature={} maxTokens={} messageCount={} toolsCount={} hasToolCall={} finishReason={} elapsedMs={} promptTokens={} completionTokens={} totalTokens={}",
                context.getRequestId(),
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
