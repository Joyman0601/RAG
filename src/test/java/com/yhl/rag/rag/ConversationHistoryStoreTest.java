package com.yhl.rag.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ConversationHistoryStoreTest {

    @Test
    void appendThenGet_returnsTurnsInOrder() {
        ConversationHistoryStore store = new ConversationHistoryStore();

        store.append("u1", "c1", new ConversationTurn("ThinkPad X1 多少钱", "9999 元"));
        store.append("u1", "c1", new ConversationTurn("内存多大", "16GB"));

        ConversationHistory history = store.get("u1", "c1");
        assertThat(history.recentTurns()).extracting(ConversationTurn::userMessage)
                .containsExactly("ThinkPad X1 多少钱", "内存多大");
        assertThat(history.summary()).isEmpty();
    }

    @Test
    void differentUserOrConversation_isIsolated() {
        ConversationHistoryStore store = new ConversationHistoryStore();
        store.append("u1", "c1", new ConversationTurn("q1", "a1"));

        assertThat(store.get("u2", "c1").recentTurns()).isEmpty();
        assertThat(store.get("u1", "c2").recentTurns()).isEmpty();
        assertThat(store.get("u1", "c1").recentTurns()).hasSize(1);
    }

    @Test
    void getUnknownOrBlankConversation_returnsEmpty() {
        ConversationHistoryStore store = new ConversationHistoryStore();

        assertThat(store.get("u1", "unknown").isEmpty()).isTrue();
        assertThat(store.get("u1", null).isEmpty()).isTrue();
        assertThat(store.get("u1", "  ").isEmpty()).isTrue();
    }

    @Test
    void replace_swapsSummaryAndRecentTurns() {
        ConversationHistoryStore store = new ConversationHistoryStore();
        store.append("u1", "c1", new ConversationTurn("q1", "a1"));

        store.replace("u1", "c1", new ConversationHistory("早期摘要", List.of(new ConversationTurn("q2", "a2"))));

        ConversationHistory history = store.get("u1", "c1");
        assertThat(history.summary()).isEqualTo("早期摘要");
        assertThat(history.recentTurns()).extracting(ConversationTurn::userMessage).containsExactly("q2");
    }

    @Test
    void blankConversationId_appendIsNoop() {
        ConversationHistoryStore store = new ConversationHistoryStore();
        store.append("u1", null, new ConversationTurn("q1", "a1"));
        store.append("u1", "  ", new ConversationTurn("q2", "a2"));

        assertThat(store.get("u1", "anything").isEmpty()).isTrue();
    }
}
