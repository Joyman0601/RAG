package com.yhl.rag.agent;

import java.util.List;

import com.yhl.rag.llm.LlmMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentContextBuilder {

    private static final int MAX_STATE_CHARS = 1_000;

    private final ConversationStateService conversationStateService;

    public AgentContextBuilder(ConversationStateService conversationStateService) {
        this.conversationStateService = conversationStateService;
    }

    public List<LlmMessage> buildMessages(
            String userId,
            String conversationId,
            String message,
            List<String> allowedTools
    ) {
        ConversationState state = conversationStateService.getOrCreate(conversationId, userId);
        String stateText = buildStateText(state, allowedTools);
        return List.of(
                new LlmMessage("user", stateText),
                new LlmMessage("user", message)
        );
    }

    private String buildStateText(ConversationState state, List<String> allowedTools) {
        StringBuilder builder = new StringBuilder();
        builder.append("conversation_state:\n");
        builder.append("conversationId=").append(state.getConversationId()).append('\n');
        builder.append("userId=").append(state.getUserId()).append('\n');
        append(builder, "currentOrderId", state.getCurrentOrderId());
        append(builder, "pendingConfirmationId", state.getPendingConfirmationId());
        append(builder, "lastToolName", state.getLastToolName());
        append(builder, "lastToolResultSummary", state.getLastToolResultSummary());
        builder.append("allowedTools=").append(allowedTools).append('\n');
        builder.append("instruction=When the user says this order, it, or current order, use currentOrderId if present. Tool arguments still must be valid.\n");

        String text = builder.toString();
        if (text.length() <= MAX_STATE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_STATE_CHARS);
    }

    private void append(StringBuilder builder, String key, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(key).append('=').append(value).append('\n');
        }
    }
}
