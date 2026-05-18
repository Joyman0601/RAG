package com.yhl.rag.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = "你是一个严谨的后端和大模型应用开发助手。";
    private static final int MAX_HISTORY_MESSAGES = 12;

    private final LlmClient llmClient;
    private final ConcurrentMap<String, List<LlmMessage>> conversationStore = new ConcurrentHashMap<>();

    public ChatService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public ChatResponse chat(String conversationId, String message) {
        String actualConversationId = StringUtils.hasText(conversationId)
                ? conversationId
                : UUID.randomUUID().toString();

        List<LlmMessage> input = buildInput(actualConversationId, message);
        String answer = llmClient.generate(SYSTEM_PROMPT, input);

        appendHistory(actualConversationId, new LlmMessage("user", message));
        appendHistory(actualConversationId, new LlmMessage("assistant", answer));
        return new ChatResponse(answer, actualConversationId);
    }

    public SseEmitter streamChat(String message) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> llmClient.streamChat(SYSTEM_PROMPT, message, emitter));
        return emitter;
    }

    private List<LlmMessage> buildInput(String conversationId, String message) {
        List<LlmMessage> history = conversationStore.getOrDefault(conversationId, List.of());
        List<LlmMessage> input = new ArrayList<>(history);
        input.add(new LlmMessage("user", message));
        return input;
    }

    private void appendHistory(String conversationId, LlmMessage message) {
        conversationStore.compute(conversationId, (key, currentHistory) -> {
            List<LlmMessage> updatedHistory = currentHistory == null
                    ? new ArrayList<>()
                    : new ArrayList<>(currentHistory);
            updatedHistory.add(message);

            int fromIndex = Math.max(0, updatedHistory.size() - MAX_HISTORY_MESSAGES);
            return new ArrayList<>(updatedHistory.subList(fromIndex, updatedHistory.size()));
        });
    }
}
