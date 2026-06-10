package com.yhl.rag.observability;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 高层 Langfuse 埋点 API。
 * 每次调用生成一条 trace-create（若 traceId 新建）+ 一条 generation-create。
 * 开关关闭时全程 no-op。
 */
@Component
public class LangfuseTracer {

    private final LangfuseClient client;
    private final LangfuseProperties properties;

    public LangfuseTracer(LangfuseClient client, LangfuseProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 记录一次 LLM generation。
     *
     * @param traceId         来自 RequestContext.requestId()，同一请求内多次 LLM 调用复用同一 trace
     * @param name            generation 名称（如 "rag_ask", "query_rewrite", "agent_loop"）
     * @param model           模型名称
     * @param input           发给模型的完整 prompt（instructions + messages 拼接摘要）
     * @param output          模型原始回复
     * @param promptTokens    输入 token 数
     * @param completionTokens 输出 token 数
     * @param latencyMs       本次调用耗时
     */
    public void recordGeneration(
            String traceId,
            String name,
            String model,
            String input,
            String output,
            Integer promptTokens,
            Integer completionTokens,
            long latencyMs
    ) {
        if (!properties.isEnabled()) {
            return;
        }

        String resolvedTraceId = StringUtils.hasText(traceId) ? traceId : UUID.randomUUID().toString();
        String generationId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Map<String, Object> traceEvent = buildTraceEvent(resolvedTraceId, name, now);
        Map<String, Object> genEvent = buildGenerationEvent(
                generationId, resolvedTraceId, name, model,
                input, output, promptTokens, completionTokens, latencyMs, now);

        client.sendBatchAsync(List.of(traceEvent, genEvent));
    }

    private Map<String, Object> buildTraceEvent(String traceId, String name, String timestamp) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", traceId);
        body.put("name", name);
        body.put("timestamp", timestamp);

        Map<String, Object> event = new HashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("timestamp", timestamp);
        event.put("type", "trace-create");
        event.put("body", body);
        return event;
    }

    private Map<String, Object> buildGenerationEvent(
            String generationId, String traceId, String name, String model,
            String input, String output,
            Integer promptTokens, Integer completionTokens, long latencyMs, String timestamp
    ) {
        Map<String, Object> usage = new HashMap<>();
        if (promptTokens != null) usage.put("input", promptTokens);
        if (completionTokens != null) usage.put("output", completionTokens);
        if (promptTokens != null && completionTokens != null) {
            usage.put("total", promptTokens + completionTokens);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("id", generationId);
        body.put("traceId", traceId);
        body.put("name", name);
        body.put("model", model);
        body.put("input", input);
        body.put("output", output);
        body.put("usage", usage);
        body.put("startTime", timestamp);
        body.put("endTime", Instant.now().toString());

        Map<String, Object> event = new HashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("timestamp", timestamp);
        event.put("type", "generation-create");
        event.put("body", body);
        return event;
    }
}
