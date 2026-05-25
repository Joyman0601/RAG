package com.yhl.rag.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.tool.QueryOrderToolResult;
import com.yhl.rag.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConversationStateService {

    private static final Logger log = LoggerFactory.getLogger(ConversationStateService.class);
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("\\b[A-Za-z0-9][A-Za-z0-9_-]{2,63}\\b");

    private final ConcurrentMap<String, ConversationState> states = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public ConversationStateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ConversationState getOrCreate(String conversationId, String userId) {
        String actualConversationId = StringUtils.hasText(conversationId)
                ? conversationId.trim()
                : UUID.randomUUID().toString();
        return states.computeIfAbsent(key(actualConversationId, userId), ignored -> new ConversationState(actualConversationId, userId));
    }

    public boolean updateAfterUserMessage(ConversationState state, String message) {
        String orderId = extractOrderId(message);
        if (!StringUtils.hasText(orderId)) {
            return false;
        }
        state.setCurrentOrderId(orderId);
        state.setUpdatedAt(Instant.now());
        log.info("conversation_state_updated conversationId={} userId={} updated=true reason=USER_ORDER_ID",
                state.getConversationId(),
                state.getUserId());
        return true;
    }

    public boolean updateAfterToolResult(ConversationState state, ToolResult toolResult) {
        if (toolResult == null || !toolResult.isSuccess()) {
            return false;
        }

        state.setLastToolName(toolResult.getToolName());
        state.setLastToolResultSummary(buildSafeSummary(toolResult));
        String orderId = extractOrderId(toolResult);
        if (StringUtils.hasText(orderId)) {
            state.setCurrentOrderId(orderId);
        }
        state.setUpdatedAt(Instant.now());
        log.info("conversation_state_updated conversationId={} userId={} updated=true reason=TOOL_RESULT toolName={}",
                state.getConversationId(),
                state.getUserId(),
                toolResult.getToolName());
        return true;
    }

    public void updatePendingConfirmation(ConversationState state, String confirmationId) {
        state.setPendingConfirmationId(confirmationId);
        state.setUpdatedAt(Instant.now());
        log.info("conversation_state_updated conversationId={} userId={} updated=true reason=PENDING_CONFIRMATION",
                state.getConversationId(),
                state.getUserId());
    }

    public void clearPendingConfirmation(ConversationState state) {
        state.setPendingConfirmationId(null);
        state.setUpdatedAt(Instant.now());
    }

    public void clear(String conversationId, String userId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        states.remove(key(conversationId.trim(), userId));
        log.info("conversation_state_cleared conversationId={} userId={}", conversationId, userId);
    }

    private String extractOrderId(String message) {
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

    private boolean looksLikeOrderId(String candidate) {
        return candidate.contains("_")
                || candidate.contains("-")
                || candidate.startsWith("ORD")
                || candidate.matches(".*\\d{3,}.*");
    }

    private String extractOrderId(ToolResult toolResult) {
        JsonNode resultNode = objectMapper.valueToTree(toolResult.getResult());
        JsonNode orderIdNode = resultNode.get("orderId");
        return orderIdNode == null || !orderIdNode.isTextual() ? null : orderIdNode.asText();
    }

    private String buildSafeSummary(ToolResult toolResult) {
        if ("query_order".equals(toolResult.getToolName())) {
            QueryOrderToolResult result = objectMapper.convertValue(toolResult.getResult(), QueryOrderToolResult.class);
            BigDecimal amount = result.getAmount();
            return "orderId=" + result.getOrderId()
                    + ", status=" + result.getStatus()
                    + ", amount=" + (amount == null ? "" : amount);
        }
        JsonNode resultNode = objectMapper.valueToTree(toolResult.getResult());
        String orderId = resultNode.path("orderId").asText("");
        String status = resultNode.path("status").asText("");
        return "orderId=" + orderId + ", status=" + status;
    }

    private String key(String conversationId, String userId) {
        return userId + ":" + conversationId;
    }
}
