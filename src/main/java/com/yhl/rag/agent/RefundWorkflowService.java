package com.yhl.rag.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.tool.QueryOrderToolResult;
import com.yhl.rag.tool.SearchKnowledgeToolResult;
import com.yhl.rag.tool.ToolExecutionContext;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RefundWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(RefundWorkflowService.class);

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("\\b[A-Za-z0-9][A-Za-z0-9_-]{2,63}\\b");
    private static final String DEFAULT_CONVERSATION_ID = "default-refund-conversation";
    private static final String ORDER_TOOL = "query_order";
    private static final String KNOWLEDGE_TOOL = "search_knowledge_base";

    private static final String ORDER_EXTRACTION_PROMPT = """
            你只负责从用户消息中抽取退款申请涉及的订单号。
            只能返回 JSON，不要解释，不要输出 markdown。
            如果没有订单号，返回 {"orderId":null}。
            如果有订单号，返回 {"orderId":"订单号"}。
            不要返回 userId、tenantId、topK、scoreThreshold，也不要决定退款流程状态。
            """;

    private final ConcurrentMap<String, RefundWorkflowSession> sessions = new ConcurrentHashMap<>();

    private final ToolExecutionService toolExecutionService;

    private final AuditLogService auditLogService;

    private final LlmClient llmClient;

    private final LlmProperties llmProperties;

    private final ObjectMapper objectMapper;

    public RefundWorkflowService(
            ToolExecutionService toolExecutionService,
            AuditLogService auditLogService,
            LlmClient llmClient,
            LlmProperties llmProperties,
            ObjectMapper objectMapper
    ) {
        this.toolExecutionService = toolExecutionService;
        this.auditLogService = auditLogService;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
    }

    public RefundWorkflowResponse startOrContinue(String conversationId, String userId, String message) {
        String actualConversationId = normalizeConversationId(conversationId);
        String actualUserId = StringUtils.hasText(userId) ? userId.trim() : "user_001";
        String requestId = UUID.randomUUID().toString();
        ToolExecutionContext context = new ToolExecutionContext(
                requestId,
                actualUserId,
                "default-department",
                1
        );
        List<AgentStep> steps = new ArrayList<>();
        RefundWorkflowSession session = getOrCreateSession(actualConversationId, actualUserId, message);
        session.setRequestId(requestId);
        touch(session);

        log.info("refund_workflow_started requestId={} workflowId={} conversationId={} userId={} state={}",
                requestId,
                session.getWorkflowId(),
                actualConversationId,
                actualUserId,
                session.getState());

        if (session.getState() == RefundWorkflowState.WAITING_CONFIRMATION) {
            return handleConfirmationMessage(session, message, context, steps);
        }

        if (isTerminal(session.getState())) {
            return response(session, "当前退款流程已结束。如需处理其他订单，请在新的会话中发起退款申请。", false, steps);
        }

        String orderId = resolveOrderId(message);
        if (StringUtils.hasText(orderId) && !StringUtils.hasText(session.getOrderId())) {
            session.setOrderId(orderId);
            transition(session, RefundWorkflowState.ORDER_READY, "ORDER_ID_COLLECTED");
        }

        if (!StringUtils.hasText(session.getOrderId())) {
            transition(session, RefundWorkflowState.NEED_ORDER_ID, "ORDER_ID_MISSING");
            return response(session, "请提供需要申请退款的订单号。", false, steps);
        }

        if (session.getState() == RefundWorkflowState.INIT || session.getState() == RefundWorkflowState.NEED_ORDER_ID) {
            transition(session, RefundWorkflowState.ORDER_READY, "ORDER_ID_COLLECTED");
        }

        return advance(session, context, steps);
    }

    private RefundWorkflowResponse advance(
            RefundWorkflowSession session,
            ToolExecutionContext context,
            List<AgentStep> steps
    ) {
        while (true) {
            switch (session.getState()) {
                case ORDER_READY -> queryOrder(session, context, steps);
                case ORDER_QUERIED -> searchRefundPolicy(session, context, steps);
                case POLICY_CHECKED -> {
                    evaluateEligibility(session);
                    return responseForPolicyResult(session, steps);
                }
                case WAITING_CONFIRMATION -> {
                    return response(session, buildConfirmationAnswer(session), true, steps);
                }
                case REJECTED -> {
                    return response(session, "暂不能自动提交退款申请：" + session.getRejectReason(), false, steps);
                }
                case FAILED -> {
                    return response(session, "退款流程处理失败，请稍后重试或联系人工客服。", false, steps);
                }
                case DONE -> {
                    return response(session, "退款申请已提交。", false, steps);
                }
                default -> {
                    return response(session, "请提供需要申请退款的订单号。", false, steps);
                }
            }
        }
    }

    private void queryOrder(
            RefundWorkflowSession session,
            ToolExecutionContext context,
            List<AgentStep> steps
    ) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("orderId", session.getOrderId());
        ToolResult result = executeTool(session, steps, context, ORDER_TOOL, arguments, "orderId=" + session.getOrderId());

        if (!result.isSuccess()) {
            session.setRejectReason(safeToolFailure(result));
            transition(session, RefundWorkflowState.FAILED, "ORDER_QUERY_FAILED");
            return;
        }

        QueryOrderToolResult order = objectMapper.convertValue(result.getResult(), QueryOrderToolResult.class);
        session.setOrderSummary(buildOrderSummary(order));
        transition(session, RefundWorkflowState.ORDER_QUERIED, "ORDER_QUERY_SUCCESS");
    }

    private void searchRefundPolicy(
            RefundWorkflowSession session,
            ToolExecutionContext context,
            List<AgentStep> steps
    ) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("query", "退款政策 未发货 已发货 自动退款 人工工单");
        ToolResult result = executeTool(session, steps, context, KNOWLEDGE_TOOL, arguments, "query=refund_policy");

        if (!result.isSuccess()) {
            session.setRejectReason(safeToolFailure(result));
            transition(session, RefundWorkflowState.FAILED, "POLICY_SEARCH_FAILED");
            return;
        } else {
            SearchKnowledgeToolResult policy = objectMapper.convertValue(result.getResult(), SearchKnowledgeToolResult.class);
            session.setPolicySummary(buildPolicySummary(policy));
        }
        transition(session, RefundWorkflowState.POLICY_CHECKED, "POLICY_SEARCH_FINISHED");
    }

    private void evaluateEligibility(RefundWorkflowSession session) {
        String orderSummary = session.getOrderSummary();
        if (orderSummary != null && orderSummary.contains("logisticsStatus=SHIPPED")) {
            session.setEligible(false);
            session.setRejectReason("订单已发货，第一版 mock 规则不允许自动退款，只能创建人工工单。");
            transition(session, RefundWorkflowState.REJECTED, "POLICY_REJECTED_SHIPPED");
            return;
        }

        session.setEligible(true);
        session.setConfirmationId("refund_confirm_" + UUID.randomUUID());
        transition(session, RefundWorkflowState.WAITING_CONFIRMATION, "POLICY_APPROVED");
    }

    private RefundWorkflowResponse responseForPolicyResult(RefundWorkflowSession session, List<AgentStep> steps) {
        if (session.getState() == RefundWorkflowState.WAITING_CONFIRMATION) {
            return response(session, buildConfirmationAnswer(session), true, steps);
        }
        if (session.getState() == RefundWorkflowState.REJECTED) {
            return response(session, "暂不能自动提交退款申请：" + session.getRejectReason(), false, steps);
        }
        return response(session, "退款政策检查完成。", false, steps);
    }

    private RefundWorkflowResponse handleConfirmationMessage(
            RefundWorkflowSession session,
            String message,
            ToolExecutionContext context,
            List<AgentStep> steps
    ) {
        if (isCancel(message)) {
            transition(session, RefundWorkflowState.CANCELLED, "USER_CANCELLED");
            return response(session, "已取消本次退款申请。", false, steps);
        }

        if (!isConfirm(message)) {
            return response(session, buildConfirmationAnswer(session), true, steps);
        }

        transition(session, RefundWorkflowState.SUBMITTED, "USER_CONFIRMED");
        auditLogService.logRefundSubmitted(
                context.getRequestId(),
                session.getWorkflowId(),
                session.getUserId(),
                session.getConfirmationId(),
                session.getOrderSummary(),
                Instant.now()
        );
        transition(session, RefundWorkflowState.DONE, "MOCK_REFUND_CREATED");
        String answer = "退款申请已提交，当前为 mock 流程，不会发生真实退款。";
        return response(session, answer, false, steps);
    }

    private ToolResult executeTool(
            RefundWorkflowSession session,
            List<AgentStep> steps,
            ToolExecutionContext context,
            String toolName,
            JsonNode arguments,
            String argumentsSummary
    ) {
        steps.add(step(session, context, steps.size() + 1, AgentActionType.TOOL_CALL, toolName, argumentsSummary, true, null, 0, null));
        ToolResult result = toolExecutionService.execute(toolName, arguments, context);
        steps.add(step(
                session,
                context,
                steps.size() + 1,
                AgentActionType.TOOL_RESULT,
                toolName,
                argumentsSummary,
                result.isSuccess(),
                result.getErrorCode(),
                result.getElapsedMs(),
                result.getErrorCode()
        ));
        return result;
    }

    private AgentStep step(
            RefundWorkflowSession session,
            ToolExecutionContext context,
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
                session.getConversationId(),
                stepIndex,
                actionType,
                toolName,
                argumentsSummary,
                success,
                errorCode,
                elapsedMs,
                stopReason
        );
        log.info("refund_workflow_step requestId={} stepIndex={} actionType={} toolName={} success={} errorCode={} elapsedMs={} stopReason={}",
                step.getRequestId(),
                step.getStepIndex(),
                step.getActionType(),
                step.getToolName(),
                step.isSuccess(),
                step.getErrorCode(),
                step.getElapsedMs(),
                step.getStopReason());
        return step;
    }

    private String resolveOrderId(String message) {
        String fromRegex = extractOrderIdByRegex(message);
        if (StringUtils.hasText(fromRegex)) {
            return fromRegex;
        }
        return extractOrderIdWithModel(message);
    }

    private String extractOrderIdByRegex(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher matcher = ORDER_ID_PATTERN.matcher(message);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (looksLikeOrderId(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String extractOrderIdWithModel(String message) {
        if (!StringUtils.hasText(message) || !StringUtils.hasText(llmProperties.getApiKey())) {
            return null;
        }

        long startedAt = System.nanoTime();
        try {
            String raw = llmClient.generate(ORDER_EXTRACTION_PROMPT, List.of(new LlmMessage("user", message)));
            JsonNode root = objectMapper.readTree(cleanJson(raw));
            String orderId = root.path("orderId").isTextual() ? root.path("orderId").asText() : null;
            if (!StringUtils.hasText(orderId) || !orderId.matches("^[A-Za-z0-9_-]{3,64}$")) {
                return null;
            }
            log.info("refund_workflow_llm_extract success=true elapsedMs={}", elapsedMillis(startedAt));
            return orderId;
        } catch (LlmException | JsonProcessingException exception) {
            log.warn("refund_workflow_llm_extract success=false elapsedMs={}", elapsedMillis(startedAt), exception);
            return null;
        }
    }

    private boolean looksLikeOrderId(String candidate) {
        return candidate.contains("_")
                || candidate.contains("-")
                || candidate.startsWith("ORD")
                || candidate.matches(".*\\d{3,}.*");
    }

    private RefundWorkflowSession getOrCreateSession(String conversationId, String userId, String message) {
        String key = key(conversationId, userId);
        RefundWorkflowSession existing = sessions.get(key);
        if (existing != null && isTerminal(existing.getState()) && startsNewRefund(message)) {
            RefundWorkflowSession replacement = newSession(conversationId, userId);
            sessions.put(key, replacement);
            return replacement;
        }
        return sessions.computeIfAbsent(key, ignored -> newSession(conversationId, userId));
    }

    private RefundWorkflowSession newSession(String conversationId, String userId) {
        return new RefundWorkflowSession("refund_wf_" + UUID.randomUUID(), conversationId, userId);
    }

    private boolean startsNewRefund(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return message.contains("退款") || StringUtils.hasText(extractOrderIdByRegex(message));
    }

    private void transition(RefundWorkflowSession session, RefundWorkflowState toState, String event) {
        RefundWorkflowState fromState = session.getState();
        if (fromState == toState) {
            return;
        }
        session.setState(toState);
        touch(session);
        log.info("refund_workflow_transition requestId={} workflowId={} fromState={} toState={} event={}",
                session.getRequestId(),
                session.getWorkflowId(),
                fromState,
                toState,
                event);
        auditLogService.logRefundWorkflowTransition(
                session.getRequestId(),
                session.getWorkflowId(),
                session.getUserId(),
                fromState,
                toState,
                event
        );
    }

    private RefundWorkflowResponse response(
            RefundWorkflowSession session,
            String answer,
            boolean requiresConfirmation,
            List<AgentStep> steps
    ) {
        return new RefundWorkflowResponse(
                session.getWorkflowId(),
                session.getState(),
                answer,
                requiresConfirmation,
                requiresConfirmation ? session.getConfirmationId() : null,
                session.getRequestId(),
                steps
        );
    }

    private String buildOrderSummary(QueryOrderToolResult order) {
        BigDecimal amount = order.getAmount();
        return "orderId=" + safe(order.getOrderId())
                + ", status=" + safe(order.getStatus())
                + ", amount=" + (amount == null ? "" : amount)
                + ", logisticsStatus=" + safe(order.getLogisticsStatus());
    }

    private String buildPolicySummary(SearchKnowledgeToolResult policy) {
        if (policy == null || policy.getContexts() == null || policy.getContexts().isEmpty()) {
            return "未检索到明确退款政策，第一版使用后端 mock 规则。";
        }
        String content = policy.getContexts().get(0).getContent();
        if (!StringUtils.hasText(content)) {
            return "已检索退款政策，但首段内容为空，第一版使用后端 mock 规则。";
        }
        return limit(content.replaceAll("\\s+", " ").trim(), 260);
    }

    private String buildConfirmationAnswer(RefundWorkflowSession session) {
        return "订单已通过退款申请初步检查，请确认是否提交退款申请。"
                + " 确认后只会创建 mock 退款申请，不会发生真实退款。"
                + " " + session.getOrderSummary();
    }

    private String safeToolFailure(ToolResult result) {
        if (result == null) {
            return "工具执行失败";
        }
        if (StringUtils.hasText(result.getErrorMessage())) {
            return result.getErrorMessage();
        }
        return StringUtils.hasText(result.getErrorCode()) ? result.getErrorCode() : "工具执行失败";
    }

    private boolean isConfirm(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("确认")
                || normalized.contains("同意")
                || normalized.contains("提交")
                || normalized.contains("yes")
                || normalized.contains("ok");
    }

    private boolean isCancel(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("取消")
                || normalized.contains("不要")
                || normalized.contains("算了")
                || normalized.contains("no");
    }

    private boolean isTerminal(RefundWorkflowState state) {
        return state == RefundWorkflowState.DONE
                || state == RefundWorkflowState.REJECTED
                || state == RefundWorkflowState.CANCELLED
                || state == RefundWorkflowState.FAILED;
    }

    private void touch(RefundWorkflowSession session) {
        session.setUpdatedAt(Instant.now());
    }

    private String normalizeConversationId(String conversationId) {
        return StringUtils.hasText(conversationId) ? conversationId.trim() : DEFAULT_CONVERSATION_ID;
    }

    private String key(String conversationId, String userId) {
        return userId + ":" + conversationId;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String limit(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
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
}
