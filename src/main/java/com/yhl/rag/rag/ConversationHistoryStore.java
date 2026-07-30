package com.yhl.rag.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 轻量内存会话历史存储（demo 级）：按 userId+conversationId 隔离，保存滚动摘要 + 最近轮次。
 * 仅多轮会话改写用；进程内 Map，不持久化。租户/用户隔离对齐 ConversationStateService 的 key 约定。
 */
@Component
public class ConversationHistoryStore {

    private final ConcurrentMap<String, ConversationHistory> histories = new ConcurrentHashMap<>();

    public ConversationHistory get(String userId, String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return ConversationHistory.empty();
        }
        return histories.getOrDefault(key(userId, conversationId), ConversationHistory.empty());
    }

    public void append(String userId, String conversationId, ConversationTurn turn) {
        if (!StringUtils.hasText(conversationId) || turn == null) {
            return;
        }
        histories.compute(key(userId, conversationId), (ignored, existing) -> {
            ConversationHistory base = existing == null ? ConversationHistory.empty() : existing;
            List<ConversationTurn> turns = new ArrayList<>(base.recentTurns());
            turns.add(turn);
            return new ConversationHistory(base.summary(), List.copyOf(turns));
        });
    }

    /** 压缩后整体替换：summary 吸收早期轮次，recentTurns 只保留最近若干轮。 */
    public void replace(String userId, String conversationId, ConversationHistory history) {
        if (!StringUtils.hasText(conversationId) || history == null) {
            return;
        }
        histories.put(key(userId, conversationId), history);
    }

    public void clear(String userId, String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        histories.remove(key(userId, conversationId));
    }

    private String key(String userId, String conversationId) {
        return userId + ":" + conversationId.trim();
    }
}
