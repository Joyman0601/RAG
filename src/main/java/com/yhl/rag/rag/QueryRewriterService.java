package com.yhl.rag.rag;

import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class QueryRewriterService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriterService.class);

    private static final String REWRITE_INSTRUCTIONS = """
            你是一个检索 query 优化助手。
            将用户的口语化问题改写成更适合文档检索的查询语句：提取核心关键词，补全省略，去除口语化填充词。
            只输出改写后的查询语句，不做任何解释，不加引号。
            """;

    private final LlmClient llmClient;
    private final RagProperties ragProperties;

    public QueryRewriterService(LlmClient llmClient, RagProperties ragProperties) {
        this.llmClient = llmClient;
        this.ragProperties = ragProperties;
    }

    /**
     * 把口语化问题改写成检索友好的 query。
     * 关闭开关、空问题、LLM 失败、输出为空时均降级返回原 question。
     */
    public String rewrite(String question) {
        if (!ragProperties.getQueryRewrite().isEnabled() || !StringUtils.hasText(question)) {
            return question;
        }

        try {
            LlmGenerationResult result = llmClient.generateWithUsage(
                    REWRITE_INSTRUCTIONS,
                    List.of(new LlmMessage("user", question))
            );
            String rewritten = result.getAnswer() == null ? "" : result.getAnswer().trim();
            if (!StringUtils.hasText(rewritten)) {
                log.warn("rag_query_rewrite_empty original={}", question);
                return question;
            }
            boolean changed = !rewritten.equals(question);
            log.info("rag_query_rewrite original={} rewritten={} changed={}", question, rewritten, changed);
            return rewritten;
        } catch (LlmException e) {
            log.warn("rag_query_rewrite_fallback errorType={} message={}", e.getErrorType(), e.getMessage());
            return question;
        }
    }
}
