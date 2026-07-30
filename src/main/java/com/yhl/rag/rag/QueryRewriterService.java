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

    private static final String CONVERSATIONAL_INSTRUCTIONS = """
            你是一个对话式检索 query 优化助手。
            结合下面的对话历史，把用户的最新追问改写成不依赖上下文、可独立检索的查询语句：
            消解指代（把"它/这个/那个/上面说的"等替换成对话中提到的具体实体），补全被省略的主语或限定词。
            只输出改写后的查询语句，不做任何解释，不加引号；若追问本身已自包含则原样输出其检索关键词。
            """;

    private static final String SUMMARY_INSTRUCTIONS = """
            你是一个对话摘要助手。
            把已有摘要与下面较早的若干轮对话压缩成一段简洁中文摘要：
            保留关键实体、用户意图与已确认的结论，丢弃寒暄与冗余。只输出摘要正文。
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

    /**
     * 结合对话历史的指代消解式改写。
     * 会话开关关 / 总开关关 / 历史为空 时回退单轮 {@link #rewrite(String)}（无 conversationId 即走此路，零回归）；
     * 空问题原样返回；LLM 失败或返回空时降级为原问题。
     */
    public String rewrite(String question, ConversationHistory history) {
        boolean conversationEnabled = ragProperties.getQueryRewrite().getConversation().isEnabled();
        if (!ragProperties.getQueryRewrite().isEnabled()
                || !conversationEnabled
                || history == null
                || history.isEmpty()) {
            return rewrite(question);
        }
        if (!StringUtils.hasText(question)) {
            return question;
        }

        String userMessage = buildConversationalPrompt(question, history);
        try {
            LlmGenerationResult result = llmClient.generateWithUsage(
                    CONVERSATIONAL_INSTRUCTIONS,
                    List.of(new LlmMessage("user", userMessage))
            );
            String rewritten = result.getAnswer() == null ? "" : result.getAnswer().trim();
            if (!StringUtils.hasText(rewritten)) {
                log.warn("rag_conversational_rewrite_empty original={}", question);
                return question;
            }
            boolean changed = !rewritten.equals(question);
            log.info("rag_conversational_rewrite original={} rewritten={} changed={} historyTurns={}",
                    question, rewritten, changed, history.recentTurns().size());
            return rewritten;
        } catch (LlmException e) {
            log.warn("rag_conversational_rewrite_fallback errorType={} message={}", e.getErrorType(), e.getMessage());
            return question;
        }
    }

    /**
     * 把已有摘要 + 较早轮次压缩成新摘要。无可压缩轮次时原样返回 existingSummary；
     * LLM 失败 / 返回空时返回 null（调用方据此放弃本次压缩，避免丢历史）。
     */
    public String summarizeHistory(String existingSummary, List<ConversationTurn> turnsToSummarize) {
        if (turnsToSummarize == null || turnsToSummarize.isEmpty()) {
            return existingSummary;
        }

        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(existingSummary)) {
            sb.append("已有摘要：\n").append(existingSummary).append("\n\n");
        }
        sb.append("较早对话：\n").append(renderTurns(turnsToSummarize));
        try {
            LlmGenerationResult result = llmClient.generateWithUsage(
                    SUMMARY_INSTRUCTIONS,
                    List.of(new LlmMessage("user", sb.toString()))
            );
            String summary = result.getAnswer() == null ? "" : result.getAnswer().trim();
            if (!StringUtils.hasText(summary)) {
                log.warn("rag_conversation_summary_empty turns={}", turnsToSummarize.size());
                return null;
            }
            log.info("rag_conversation_summary compressedTurns={} summaryLength={}", turnsToSummarize.size(), summary.length());
            return summary;
        } catch (LlmException e) {
            log.warn("rag_conversation_summary_fallback errorType={} message={}", e.getErrorType(), e.getMessage());
            return null;
        }
    }

    private String buildConversationalPrompt(String question, ConversationHistory history) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(history.summary())) {
            sb.append("对话历史摘要：\n").append(history.summary()).append("\n\n");
        }
        if (history.recentTurns() != null && !history.recentTurns().isEmpty()) {
            sb.append("最近对话：\n").append(renderTurns(history.recentTurns())).append("\n");
        }
        sb.append("当前追问：").append(question);
        return sb.toString();
    }

    private static String renderTurns(List<ConversationTurn> turns) {
        StringBuilder sb = new StringBuilder();
        for (ConversationTurn turn : turns) {
            if (StringUtils.hasText(turn.userMessage())) {
                sb.append("用户：").append(turn.userMessage()).append("\n");
            }
            if (StringUtils.hasText(turn.assistantMessage())) {
                sb.append("助手：").append(turn.assistantMessage()).append("\n");
            }
        }
        return sb.toString();
    }
}
