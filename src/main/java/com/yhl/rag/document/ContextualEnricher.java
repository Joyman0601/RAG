package com.yhl.rag.document;

import java.util.List;

import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.rag.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Contextual Retrieval（Anthropic 2024）：入库时用 LLM 为子块生成一句「在父块/全文中的定位」前缀，
 * 拼到待 embedding 文本前提升召回；展示/回填仍用原文。
 *
 * 把「父块/全文」放进 system instructions —— LlmClient 在 cache 开启时对 system 块注入 cache_control，
 * 故同一父块/全文的多个子块共享同一可缓存前缀，靠 Prompt Caching 压成本。
 *
 * 任何不利条件（开关关 / 无 LlmClient / 上下文或正文为空 / LLM 调用失败 / 返回空）都降级为返回原文，
 * 不加前缀、不阻断入库，保证零回归。
 */
@Component
public class ContextualEnricher {

    private static final Logger log = LoggerFactory.getLogger(ContextualEnricher.class);

    private static final String INSTRUCTIONS_HEADER = """
            你是文档检索的上下文标注助手。下面给出一篇文档（或其父级章节）的完整内容，作为理解其中片段的背景。
            稍后用户会给你其中的一个片段，请只输出一句不超过 50 字的中文定位说明：
            概括该片段在整体中的位置与主题，便于检索时与其它片段区分。
            只输出这句话本身，不要解释、不要加引号、不要复述原文。

            文档内容：
            """;

    /** 父块/全文超长时截断，避免撑爆模型输入；前缀对相同截断结果稳定，不影响缓存命中。 */
    private static final int MAX_CONTEXT_CHARS = 8000;

    private final LlmClient llmClient;
    private final RagProperties ragProperties;

    @Autowired
    public ContextualEnricher(LlmClient llmClient, RagProperties ragProperties) {
        this.llmClient = llmClient;
        this.ragProperties = ragProperties;
    }

    /**
     * 返回待 embedding 文本：开启且成功时为「定位前缀 + 换行 + 原文」，否则原文不变。
     *
     * @param content       子块原文（展示/回填用的也是它，不被改写）
     * @param contextSource 父块正文或文档全文，作为可缓存前缀供 LLM 理解定位
     */
    public String buildEmbeddingText(String content, String contextSource) {
        if (!ragProperties.getContextual().isEnabled()
                || llmClient == null
                || !StringUtils.hasText(content)
                || !StringUtils.hasText(contextSource)) {
            return content;
        }

        try {
            String instructions = INSTRUCTIONS_HEADER + truncate(contextSource);
            String prefix = llmClient.generate(
                    instructions,
                    List.of(new LlmMessage("user", "片段：\n" + content))
            );
            prefix = prefix == null ? "" : prefix.trim();
            if (!StringUtils.hasText(prefix)) {
                log.warn("contextual_enrich_empty contentChars={}", content.length());
                return content;
            }
            log.info("contextual_enrich_applied prefixChars={} contentChars={}", prefix.length(), content.length());
            return prefix + "\n" + content;
        } catch (LlmException exception) {
            log.warn("contextual_enrich_fallback errorType={} message={}",
                    exception.getErrorType(), exception.getMessage());
            return content;
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_CONTEXT_CHARS ? text : text.substring(0, MAX_CONTEXT_CHARS);
    }
}
