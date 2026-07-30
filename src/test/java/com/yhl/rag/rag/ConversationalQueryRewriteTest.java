package com.yhl.rag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationalQueryRewriteTest {

    private LlmClient llmClient;
    private RagProperties ragProperties;
    private QueryRewriterService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        ragProperties = new RagProperties();
        service = new QueryRewriterService(llmClient, ragProperties);
        // 多轮改写以单轮总开关为前提。
        ragProperties.getQueryRewrite().setEnabled(true);
        ragProperties.getQueryRewrite().getConversation().setEnabled(true);
    }

    private ConversationHistory historyWith(ConversationTurn... turns) {
        return new ConversationHistory("", List.of(turns));
    }

    @Test
    void coreferenceResolution_rewritesPronounToEntity() {
        ConversationHistory history = historyWith(
                new ConversationTurn("ThinkPad X1 的价格是多少", "ThinkPad X1 售价 9999 元")
        );
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenReturn(new LlmGenerationResult("ThinkPad X1 的内存大小", 20, 6, 26));

        String result = service.rewrite("它的内存呢", history);

        assertThat(result).isEqualTo("ThinkPad X1 的内存大小");
        // 历史与当前追问都进入了喂给 LLM 的 user 消息。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).generateWithUsage(anyString(), captor.capture());
        String userMessage = captor.getValue().get(0).content();
        assertThat(userMessage).contains("ThinkPad X1 的价格是多少");
        assertThat(userMessage).contains("它的内存呢");
    }

    @Test
    void emptyHistory_fallsBackToSingleTurn() {
        // 空历史（无 conversationId 场景）→ 走单轮改写指令，不做指代消解。
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenReturn(new LlmGenerationResult("请假所需材料", 10, 4, 14));

        String result = service.rewrite("我那个请假的事儿要准备啥", ConversationHistory.empty());

        assertThat(result).isEqualTo("请假所需材料");
        ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generateWithUsage(instructions.capture(), any());
        assertThat(instructions.getValue()).contains("口语化");
    }

    @Test
    void conversationDisabled_fallsBackToSingleTurn() {
        ragProperties.getQueryRewrite().getConversation().setEnabled(false);
        ConversationHistory history = historyWith(new ConversationTurn("ThinkPad X1 价格", "9999"));
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenReturn(new LlmGenerationResult("ThinkPad", 5, 1, 6));

        String result = service.rewrite("它的内存呢", history);

        assertThat(result).isEqualTo("ThinkPad");
        ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generateWithUsage(instructions.capture(), any());
        assertThat(instructions.getValue()).doesNotContain("对话式");
    }

    @Test
    void masterRewriteDisabled_returnsOriginalWithoutLlm() {
        ragProperties.getQueryRewrite().setEnabled(false);
        ConversationHistory history = historyWith(new ConversationTurn("ThinkPad X1 价格", "9999"));

        String result = service.rewrite("它的内存呢", history);

        assertThat(result).isEqualTo("它的内存呢");
        verifyNoInteractions(llmClient);
    }

    @Test
    void llmThrows_degradesToOriginalQuestion() {
        ConversationHistory history = historyWith(new ConversationTurn("ThinkPad X1 价格", "9999"));
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenThrow(new LlmException(LlmErrorType.TIMEOUT, "timeout"));

        String result = service.rewrite("它的内存呢", history);

        assertThat(result).isEqualTo("它的内存呢");
    }

    @Test
    void llmReturnsBlank_degradesToOriginalQuestion() {
        ConversationHistory history = historyWith(new ConversationTurn("ThinkPad X1 价格", "9999"));
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenReturn(new LlmGenerationResult("   ", 5, 1, 6));

        String result = service.rewrite("它的内存呢", history);

        assertThat(result).isEqualTo("它的内存呢");
    }

    @Test
    void summarizeHistory_compressesEarlyTurns() {
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenReturn(new LlmGenerationResult("用户咨询 ThinkPad X1 的价格与内存。", 30, 12, 42));

        String summary = service.summarizeHistory("", List.of(
                new ConversationTurn("ThinkPad X1 价格", "9999"),
                new ConversationTurn("内存呢", "16GB")
        ));

        assertThat(summary).isEqualTo("用户咨询 ThinkPad X1 的价格与内存。");
    }

    @Test
    void summarizeHistory_noTurns_returnsExistingSummary() {
        String summary = service.summarizeHistory("旧摘要", List.of());

        assertThat(summary).isEqualTo("旧摘要");
        verifyNoInteractions(llmClient);
    }

    @Test
    void summarizeHistory_llmThrows_returnsNull() {
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenThrow(new LlmException(LlmErrorType.TIMEOUT, "timeout"));

        String summary = service.summarizeHistory("旧摘要", List.of(new ConversationTurn("q", "a")));

        assertThat(summary).isNull();
    }
}
