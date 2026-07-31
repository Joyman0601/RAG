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
     * @param input           发给模型的输入（推荐 List<Map<String,String>> message 数组，Langfuse UI
     *                        会渲染成聊天气泡；也可传 String，DB 会存但 UI 只显示 raw JSON）
     * @param output          模型回复（推荐 Map.of("role","assistant","content",answer)）
     * @param promptTokens    输入 token 数
     * @param completionTokens 输出 token 数
     * @param latencyMs       本次调用耗时
     * @param cachedTokens    Prompt Caching 命中的 token 数（不支持时为 null）
     */
    public void recordGeneration(
            String traceId,
            String name,
            String model,
            Object input,
            Object output,
            Integer promptTokens,
            Integer completionTokens,
            long latencyMs,
            Integer cachedTokens
    ) {
        if (!properties.isEnabled()) {
            return;
        }

        String resolvedTraceId = StringUtils.hasText(traceId) ? traceId : UUID.randomUUID().toString();
        String generationId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Map<String, Object> traceEvent = buildTraceEvent(resolvedTraceId, name, now, input, output);
        Map<String, Object> genEvent = buildGenerationEvent(
                generationId, resolvedTraceId, name, model,
                input, output, promptTokens, completionTokens, latencyMs, cachedTokens, now);

        client.sendBatchAsync(List.of(traceEvent, genEvent));
    }

    /** 兼容旧签名（无 Prompt Caching），cachedTokens 传 null。 */
    public void recordGeneration(
            String traceId,
            String name,
            String model,
            Object input,
            Object output,
            Integer promptTokens,
            Integer completionTokens,
            long latencyMs
    ) {
        recordGeneration(traceId, name, model, input, output, promptTokens, completionTokens, latencyMs, null);
    }

    private Map<String, Object> buildTraceEvent(String traceId, String name, String timestamp, Object input, Object output) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", traceId);
        body.put("name", name);
        body.put("timestamp", timestamp);
        if (input != null) body.put("input", input);
        if (output != null) body.put("output", output);

        Map<String, Object> event = new HashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("timestamp", timestamp);
        event.put("type", "trace-create");
        event.put("body", body);
        return event;
    }

    private Map<String, Object> buildGenerationEvent(
            String generationId, String traceId, String name, String model,
            Object input, Object output,
            Integer promptTokens, Integer completionTokens, long latencyMs, Integer cachedTokens, String timestamp
    ) {
        Map<String, Object> usage = new HashMap<>();
        if (promptTokens != null) usage.put("input", promptTokens);
        if (completionTokens != null) usage.put("output", completionTokens);
        if (promptTokens != null && completionTokens != null) {
            usage.put("total", promptTokens + completionTokens);
        }
        if (cachedTokens != null) usage.put("input_cached", cachedTokens);

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
