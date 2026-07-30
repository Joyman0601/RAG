package com.yhl.rag.rag;

import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class QueryRewriterServiceTest {

    private LlmClient llmClient;
    private RagProperties ragProperties;
    private QueryRewriterService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        ragProperties = new RagProperties();
        service = new QueryRewriterService(llmClient, ragProperties);
    }

    @Test
    void disabled_returnsOriginalWithoutCallingLlm() {
        ragProperties.getQueryRewrite().setEnabled(false);

        String result = service.rewrite("我那个请假的事儿要准备啥");

        assertThat(result).isEqualTo("我那个请假的事儿要准备啥");
        verifyNoInteractions(llmClient);
    }

    @Test
    void enabled_llmReturnsRewrite_returnsRewritten() {
        ragProperties.getQueryRewrite().setEnabled(true);
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenReturn(new LlmGenerationResult("请假所需材料", 10, 5, 15));

        String result = service.rewrite("我那个请假的事儿要准备啥");

        assertThat(result).isEqualTo("请假所需材料");
        verify(llmClient, times(1)).generateWithUsage(anyString(), any());
    }

    @Test
    void enabled_llmThrows_fallbackToOriginal() {
        ragProperties.getQueryRewrite().setEnabled(true);
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenThrow(new LlmException(LlmErrorType.TIMEOUT, "timeout"));

        String result = service.rewrite("我那个请假的事儿要准备啥");

        assertThat(result).isEqualTo("我那个请假的事儿要准备啥");
    }

    @Test
    void enabled_llmReturnsEmpty_fallbackToOriginal() {
        ragProperties.getQueryRewrite().setEnabled(true);
        when(llmClient.generateWithUsage(anyString(), any()))
                .thenReturn(new LlmGenerationResult("  ", 5, 1, 6));

        String result = service.rewrite("我那个请假的事儿要准备啥");

        assertThat(result).isEqualTo("我那个请假的事儿要准备啥");
    }

    @Test
    void nullQuestion_returnsNull() {
        ragProperties.getQueryRewrite().setEnabled(true);

        String result = service.rewrite(null);

        assertThat(result).isNull();
        verifyNoInteractions(llmClient);
    }
}
