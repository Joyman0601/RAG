package com.yhl.rag.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.observability.MetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Prompt-caching before/after quantification harness. Disabled by default; only runs when
 * DASHSCOPE_CACHE_RUN=true and DASHSCOPE_API_KEY is set in the environment.
 *
 * <p>Drives the production {@link LlmClient} chat path directly through a fixed multi-turn
 * conversation whose system prefix stays constant while the user/assistant tail grows — exactly
 * the shape that prefix caching rewards. Three scenarios are compared to isolate the two levers:
 * <ul>
 *   <li><b>short_on</b>  — the ORIGINAL short Agent prefix (&lt;1024 tokens) + cache_control on.
 *       Demonstrates that below DashScope's ~1024-token threshold the cache can never trigger.</li>
 *   <li><b>long_off</b>  — the EXPANDED prefix (&gt;1024 tokens) with no cache_control marker.</li>
 *   <li><b>long_on</b>   — the EXPANDED prefix + cache_control on. The intended "after fix" state.</li>
 * </ul>
 * Each scenario prepends a unique run nonce so the implicit/auto cache of one scenario cannot
 * warm another (DashScope may serve an implicit hit within the 5-min TTL even without a marker).
 * Writes prompt-caching-report.md / .json at the project root.
 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_CACHE_RUN", matches = "true")
class AgentPromptCachingHarness {

    /** Illustrative only: assume a cache-read is billed at this fraction of a normal input token. */
    private static final double CACHE_READ_DISCOUNT = 0.2;

    /** Realistic tool-definitions block fed into the %s slot of both prefixes (kept byte-stable). */
    private static final String TOOL_DEFINITIONS_JSON = """
            [{"name":"query_order","description":"查询某一笔订单的状态、物流、金额与时间等结构化信息","parameterSchema":{"type":"object","properties":{"orderId":{"type":"string","description":"订单号"}},"required":["orderId"]},"permissionCode":"order:query","riskLevel":"LOW"},\
            {"name":"cancel_order","description":"取消一笔已存在的订单，高风险写操作，后端会要求用户二次确认","parameterSchema":{"type":"object","properties":{"orderId":{"type":"string","description":"订单号"}},"required":["orderId"]},"permissionCode":"order:cancel","riskLevel":"HIGH"},\
            {"name":"search_knowledge_base","description":"检索企业制度、产品文档、FAQ 等静态非个人内部资料","parameterSchema":{"type":"object","properties":{"query":{"type":"string","description":"自然语言检索词"}},"required":["query"]},"permissionCode":"knowledge:search","riskLevel":"LOW"}]""";

    /** The ORIGINAL (pre-expansion) Agent prefix, reproduced verbatim for the before/after baseline. */
    private static final String SHORT_PROMPT = """
            你是一个受控企业客服 Agent。你不能直接执行工具，只能请求后端调用工具。

            当前用户可用工具如下：
            %s

            规则：
            - 如果你已经能回答用户，返回 final answer。
            - 如果需要工具，最多一次只请求一个 toolCall。
            - 查询订单使用 query_order。
            - 取消订单使用 cancel_order，但这是高风险工具，后端会要求用户确认。
            - 查询企业知识库、制度文档、产品文档或内部资料使用 search_knowledge_base。
            - search_knowledge_base 不能用于查询订单、用户隐私或实时业务数据。
            - 不要把 userId、tenantId、topK、scoreThreshold 放入工具参数，真实用户身份和检索范围由后端提供。
            - 不要重复请求相同 toolName 和相同 arguments。

            你必须只返回 JSON，不要返回 markdown，不要解释。
            返回格式只能是以下二选一：
            {"answer":"最终回复用户的话","toolCall":null}
            {"answer":null,"toolCall":{"toolName":"工具名","arguments":{"orderId":"订单号"}}}
            """;

    /** User turns of a representative multi-turn agent session. Each triggers one LLM call. */
    private static final List<String> USER_TURNS = List.of(
            "帮我查一下订单 A100086 现在到哪了",
            "tool message:\n{\"toolName\":\"query_order\",\"success\":true,\"result\":{\"orderId\":\"A100086\",\"status\":\"已发货\",\"eta\":\"明天\"}}",
            "那这个订单还能取消吗",
            "tool message:\n{\"toolName\":\"query_order\",\"success\":true,\"result\":{\"orderId\":\"A100086\",\"status\":\"已发货\",\"cancelable\":false}}",
            "好的，那你们的七天无理由退货是怎么规定的",
            "tool message:\n{\"toolName\":\"search_knowledge_base\",\"success\":true,\"result\":{\"answerable\":true,\"contexts\":[{\"content\":\"自签收次日起 7 日内可申请无理由退货，商品须不影响二次销售。\"}]}}"
    );

    /** Canned assistant replies appended after each call so history grows but the prefix is fixed. */
    private static final List<String> ASSISTANT_TURNS = List.of(
            "{\"answer\":null,\"toolCall\":{\"toolName\":\"query_order\",\"arguments\":{\"orderId\":\"A100086\"}}}",
            "{\"answer\":\"您的订单 A100086 已发货，预计明天送达。\",\"toolCall\":null}",
            "{\"answer\":null,\"toolCall\":{\"toolName\":\"query_order\",\"arguments\":{\"orderId\":\"A100086\"}}}",
            "{\"answer\":\"该订单已发货，目前无法直接取消，建议拒收或收货后申请退货。\",\"toolCall\":null}",
            "{\"answer\":null,\"toolCall\":{\"toolName\":\"search_knowledge_base\",\"arguments\":{\"query\":\"七天无理由退货 规定 条件\"}}}",
            "{\"answer\":\"自签收次日起 7 日内可申请无理由退货，商品需不影响二次销售。\",\"toolCall\":null}"
    );

    private static LlmClient buildClient(boolean cacheEnabled) {
        LlmProperties props = new LlmProperties();
        props.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
        props.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        props.setModel(System.getenv().getOrDefault("DASHSCOPE_MODEL", "qwen-plus"));
        props.setApiStyle("chat");
        props.setCacheEnabled(cacheEnabled);
        props.setTemperature(0.0);
        props.setMaxOutputTokens(64);
        props.setMaxInputChars(100000);
        props.setTimeoutSeconds(60);
        return new LlmClient(RestClient.builder(), props, new ObjectMapper(), new MetricsService());
    }

    @Test
    void quantifyBeforeAfter() throws Exception {
        String shortPrefix = SHORT_PROMPT.formatted(TOOL_DEFINITIONS_JSON);
        String longPrefix = AgentLoopService.LOOP_PROMPT.formatted(TOOL_DEFINITIONS_JSON);

        List<ScenarioResult> results = new ArrayList<>();
        results.add(runScenario("short_on", shortPrefix, true));
        results.add(runScenario("long_off", longPrefix, false));
        results.add(runScenario("long_on", longPrefix, true));

        for (ScenarioResult r : results) {
            System.out.println(r.summaryLine());
            for (CallMetric c : r.calls) {
                System.out.println("  " + c);
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        List<Object> export = new ArrayList<>();
        for (ScenarioResult r : results) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("scenario", r.name);
            m.put("cacheEnabled", r.cacheEnabled);
            m.put("calls", r.calls);
            m.put("totalPrompt", r.totalPrompt());
            m.put("totalCached", r.totalCached());
            m.put("hitRatio", r.hitRatio());
            m.put("avgLatencyMs", r.avgLatency());
            m.put("billedInputEquivalent", r.billedInputEquivalent());
            m.put("inputSavedPct", r.inputSavedPct());
            export.add(m);
        }
        Files.writeString(Paths.get("prompt-caching-report.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(export), StandardCharsets.UTF_8);
        Files.writeString(Paths.get("prompt-caching-report.md"), renderMarkdown(results), StandardCharsets.UTF_8);
        System.out.println("[prompt-caching] report written to "
                + Paths.get("prompt-caching-report.md").toAbsolutePath());
    }

    private ScenarioResult runScenario(String name, String basePrefix, boolean cacheEnabled)
            throws InterruptedException {
        // Unique nonce so this scenario gets its own cache namespace (no cross-scenario warming).
        String prefix = "[run " + UUID.randomUUID() + "]\n" + basePrefix;
        LlmClient client = buildClient(cacheEnabled);
        List<LlmMessage> messages = new ArrayList<>();
        List<CallMetric> metrics = new ArrayList<>();

        for (int i = 0; i < USER_TURNS.size(); i++) {
            messages.add(new LlmMessage("user", USER_TURNS.get(i)));
            long t0 = System.nanoTime();
            LlmGenerationResult result = client.generateWithUsage(prefix, messages);
            long latencyMs = (System.nanoTime() - t0) / 1_000_000;
            metrics.add(new CallMetric(i + 1, latencyMs,
                    result.getPromptTokens(), result.getCachedTokens(), result.getCacheCreationInputTokens()));
            messages.add(new LlmMessage("assistant", ASSISTANT_TURNS.get(i)));
            Thread.sleep(800); // let the cache become readable before the next call
        }
        return new ScenarioResult(name, cacheEnabled, metrics);
    }

    private static String renderMarkdown(List<ScenarioResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Prompt caching before/after report\n\n");
        sb.append("Model: ").append(System.getenv().getOrDefault("DASHSCOPE_MODEL", "qwen-plus"))
                .append(" · calls/scenario: ").append(USER_TURNS.size())
                .append(" · cache-read discount assumed: ").append(CACHE_READ_DISCOUNT).append("\n\n");

        sb.append("## Scenario comparison\n\n");
        sb.append("| scenario | cache_control | calls | Σ prompt | Σ cached | hit ratio | avg latency(ms) | billed-input-equiv | input saved |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (ScenarioResult r : results) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %.1f%% | %.0f | %.0f | %.1f%% |\n",
                    r.name, r.cacheEnabled ? "on" : "off", r.calls.size(),
                    r.totalPrompt(), r.totalCached(), r.hitRatio() * 100,
                    r.avgLatency(), r.billedInputEquivalent(), r.inputSavedPct()));
        }
        sb.append("\n");

        for (ScenarioResult r : results) {
            sb.append("## Per-call — ").append(r.name).append("\n\n");
            sb.append("| call | latency(ms) | prompt | cached | creation | hit? |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (CallMetric c : r.calls) {
                sb.append(String.format(Locale.ROOT, "| %d | %d | %s | %s | %s | %s |\n",
                        c.index, c.latencyMs, c.promptTokens, c.cachedTokens, c.cacheCreationTokens,
                        (c.cachedTokens != null && c.cachedTokens > 0) ? "Y" : "N"));
            }
            sb.append("\n");
        }

        sb.append("> billed-input-equiv = (Σprompt − Σcached) + Σcached × discount. ");
        sb.append("Token counts are the real DashScope numbers; the monetary discount is an ");
        sb.append("illustrative assumption — substitute the provider's actual cache-read price.\n");
        return sb.toString();
    }

    record CallMetric(int index, long latencyMs, Integer promptTokens,
                      Integer cachedTokens, Integer cacheCreationTokens) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "call#%d latency=%dms prompt=%s cached=%s creation=%s",
                    index, latencyMs, promptTokens, cachedTokens, cacheCreationTokens);
        }
    }

    static final class ScenarioResult {
        final String name;
        final boolean cacheEnabled;
        final List<CallMetric> calls;

        ScenarioResult(String name, boolean cacheEnabled, List<CallMetric> calls) {
            this.name = name;
            this.cacheEnabled = cacheEnabled;
            this.calls = calls;
        }

        long totalPrompt() {
            return calls.stream().mapToLong(c -> c.promptTokens == null ? 0 : c.promptTokens).sum();
        }

        long totalCached() {
            return calls.stream().mapToLong(c -> c.cachedTokens == null ? 0 : c.cachedTokens).sum();
        }

        double hitRatio() {
            long prompt = totalPrompt();
            return prompt == 0 ? 0.0 : (double) totalCached() / prompt;
        }

        double avgLatency() {
            return calls.stream().mapToLong(c -> c.latencyMs).average().orElse(0.0);
        }

        double billedInputEquivalent() {
            return (totalPrompt() - totalCached()) + totalCached() * CACHE_READ_DISCOUNT;
        }

        double inputSavedPct() {
            long prompt = totalPrompt();
            return prompt == 0 ? 0.0 : (1.0 - billedInputEquivalent() / prompt) * 100.0;
        }

        String summaryLine() {
            return String.format(Locale.ROOT,
                    "[%s] cache=%s Σprompt=%d Σcached=%d hit=%.1f%% avgLatency=%.0fms saved=%.1f%%",
                    name, cacheEnabled, totalPrompt(), totalCached(), hitRatio() * 100,
                    avgLatency(), inputSavedPct());
        }
    }
}
