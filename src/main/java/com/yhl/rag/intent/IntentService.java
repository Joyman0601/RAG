package com.yhl.rag.intent;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import com.yhl.rag.llm.LlmMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class IntentService {

    private static final String SYSTEM_PROMPT = """
            你是一个意图识别分类器。
            只允许识别以下三个 intent：
            - chat：普通闲聊、开放问答、非知识库和非订单问题
            - rag_query：需要查询知识库、文档、资料、规章、产品说明的问题
            - order_query：查询订单、物流、支付、售后状态的问题

            你必须只返回 JSON，不要返回 markdown，不要解释。
            JSON 格式必须是：
            {"intent":"chat|rag_query|order_query","confidence":0.0到1.0之间的数字}
            """;

    private static final Set<String> ALLOWED_INTENTS = Set.of("chat", "rag_query", "order_query");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public IntentService(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public IntentResponse recognize(String message) {
        String rawOutput = llmClient.generate(
                SYSTEM_PROMPT,
                List.of(new LlmMessage("user", message))
        );

        IntentResult result = parseIntentResult(cleanJson(rawOutput));
        validateIntentResult(result);
        return new IntentResponse(result.getIntent(), result.getConfidence());
    }

    private IntentResult parseIntentResult(String json) {
        try {
            return objectMapper.readValue(json, IntentResult.class);
        } catch (JsonProcessingException exception) {
            throw new LlmException(
                    LlmErrorType.INVALID_STRUCTURED_OUTPUT,
                    "意图识别结果不是合法 JSON",
                    exception
            );
        }
    }

    private void validateIntentResult(IntentResult result) {
        if (result == null) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "意图识别结果为空");
        }

        if (!StringUtils.hasText(result.getIntent())) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "意图识别结果 intent 不能为空");
        }

        String intent = result.getIntent().trim();
        result.setIntent(intent);

        if (!ALLOWED_INTENTS.contains(intent)) {
            throw new LlmException(
                    LlmErrorType.INVALID_STRUCTURED_OUTPUT,
                    "意图识别结果 intent 非法，只允许 chat、rag_query、order_query"
            );
        }

        if (result.getConfidence() == null) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "意图识别结果 confidence 不能为空");
        }

        if (result.getConfidence() < 0 || result.getConfidence() > 1) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "意图识别结果 confidence 必须在 0 到 1 之间");
        }
    }

    private static String cleanJson(String rawOutput) {
        if (rawOutput == null) {
            return null;
        }

        String cleaned = rawOutput.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length()).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length()).trim();
        }
        return cleaned;
    }
}
