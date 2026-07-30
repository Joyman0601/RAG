package com.yhl.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.rag.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ContextualEnricherTest {

    private LlmClient llmClient;
    private RagProperties ragProperties;
    private ContextualEnricher enricher;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        ragProperties = new RagProperties();
        enricher = new ContextualEnricher(llmClient, ragProperties);
    }

    @Test
    void disabled_returnsContentUnchanged_withoutCallingLlm() {
        ragProperties.getContextual().setEnabled(false);

        String result = enricher.buildEmbeddingText("子块正文", "父块完整正文：安装总览");

        assertThat(result).isEqualTo("子块正文");
        verifyNoInteractions(llmClient);
    }

    @Test
    void enabled_prependsPrefixToContent_andInjectsContextSourceAsCachePrefix() {
        ragProperties.getContextual().setEnabled(true);
        when(llmClient.generate(anyString(), any()))
                .thenReturn("本片段属于安装章节的环境要求部分");

        String result = enricher.buildEmbeddingText("需要 JDK 17。", "父块完整正文：安装总览\n环境要求：JDK 17");

        // 前缀拼接：定位说明在原文之前。
        assertThat(result).isEqualTo("本片段属于安装章节的环境要求部分\n需要 JDK 17。");

        // 缓存前缀注入：父块/全文进入 system instructions（LlmClient 在此注入 cache_control），子块进 user 消息。
        ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> input = ArgumentCaptor.forClass(List.class);
        verify(llmClient).generate(instructions.capture(), input.capture());
        assertThat(instructions.getValue()).contains("父块完整正文：安装总览");
        assertThat(input.getValue().get(0).content()).contains("需要 JDK 17。");
    }

    @Test
    void enabled_llmThrows_fallsBackToContentUnchanged() {
        ragProperties.getContextual().setEnabled(true);
        when(llmClient.generate(anyString(), any()))
                .thenThrow(new LlmException(LlmErrorType.TIMEOUT, "timeout"));

        String result = enricher.buildEmbeddingText("需要 JDK 17。", "父块完整正文");

        assertThat(result).isEqualTo("需要 JDK 17。");
    }

    @Test
    void enabled_llmReturnsBlank_fallsBackToContentUnchanged() {
        ragProperties.getContextual().setEnabled(true);
        when(llmClient.generate(anyString(), any())).thenReturn("   ");

        String result = enricher.buildEmbeddingText("需要 JDK 17。", "父块完整正文");

        assertThat(result).isEqualTo("需要 JDK 17。");
    }

    @Test
    void enabled_blankContextSource_returnsContentWithoutCallingLlm() {
        ragProperties.getContextual().setEnabled(true);

        String result = enricher.buildEmbeddingText("需要 JDK 17。", "   ");

        assertThat(result).isEqualTo("需要 JDK 17。");
        verifyNoInteractions(llmClient);
    }

    @Test
    void enabled_nullLlmClient_returnsContentUnchanged() {
        ragProperties.getContextual().setEnabled(true);
        ContextualEnricher noClient = new ContextualEnricher(null, ragProperties);

        String result = noClient.buildEmbeddingText("需要 JDK 17。", "父块完整正文");

        assertThat(result).isEqualTo("需要 JDK 17。");
    }
}
