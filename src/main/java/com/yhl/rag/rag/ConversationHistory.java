package com.yhl.rag.rag;

import java.util.List;

/**
 * 会话历史快照：早期轮次压缩后的滚动摘要 + 仍保留原文的最近若干轮。
 * 改写时把 summary + recentTurns 一起喂给 LLM 做指代消解。
 */
public record ConversationHistory(String summary, List<ConversationTurn> recentTurns) {

    public static ConversationHistory empty() {
        return new ConversationHistory("", List.of());
    }

    public boolean isEmpty() {
        return (summary == null || summary.isBlank()) && (recentTurns == null || recentTurns.isEmpty());
    }
}
