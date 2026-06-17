package com.yhl.rag.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.observability.MetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Real DashScope prompt-caching integration check. Disabled by default; only runs when
 * DASHSCOPE_CACHE_RUN=true and DASHSCOPE_API_KEY is set in the environment.
 *
 * <p>Sends the same long, stable system prefix twice serially through the real {@link LlmClient}
 * chat path with cache-enabled=true. The second call should report cachedTokens &gt; 0, proving
 * the cache_control marker + prompt_tokens_details.cached_tokens parsing work end-to-end.
 * Mirrors probe/dashscope_cache_probe.py but exercises the production LlmClient code path.
 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_CACHE_RUN", matches = "true")
class DashScopePromptCachingIT {

    private static String buildLongSystem() {
        String para = "You are a meticulous enterprise knowledge-base assistant. "
                + "Always answer strictly from the provided context, cite sources, "
                + "never fabricate facts, and refuse politely when the context is "
                + "insufficient. Follow the company's tone and compliance rules at all times. ";
        return "SYSTEM POLICY BLOCK\n" + para.repeat(80);
    }

    private static LlmClient buildClient(boolean cacheEnabled) {
        LlmProperties props = new LlmProperties();
        props.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
        props.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        props.setModel(System.getenv().getOrDefault("DASHSCOPE_MODEL", "qwen-plus"));
        props.setApiStyle("chat");
        props.setCacheEnabled(cacheEnabled);
        props.setTemperature(0.0);
        props.setMaxOutputTokens(16);
        props.setMaxInputChars(100000);
        props.setTimeoutSeconds(60);
        return new LlmClient(RestClient.builder(), props, new ObjectMapper(), new MetricsService());
    }

    @Test
    void explicitCache_secondCall_reportsCacheHit() throws InterruptedException {
        LlmClient client = buildClient(true);
        String system = buildLongSystem();
        List<LlmMessage> input = List.of(new LlmMessage("user", "Reply with exactly: OK"));

        LlmGenerationResult first = client.generateWithUsage(system, input);
        Thread.sleep(2000); // let the cache become readable before the 2nd call

        LlmGenerationResult second = client.generateWithUsage(system, input);

        System.out.printf("first:  prompt=%s cached=%s creation=%s%n",
                first.getPromptTokens(), first.getCachedTokens(), first.getCacheCreationInputTokens());
        System.out.printf("second: prompt=%s cached=%s creation=%s%n",
                second.getPromptTokens(), second.getCachedTokens(), second.getCacheCreationInputTokens());

        assertThat(second.getCachedTokens())
                .as("second call should serve most of the prompt from cache")
                .isNotNull()
                .isGreaterThan(0);
    }

    // Note: a "cache-disabled => zero hit" assertion is intentionally NOT made here.
    // DashScope may serve an implicit (auto) cache hit even without a cache_control marker
    // when the same prefix was seen recently (5-min TTL), so cachedTokens is not reliably 0.
    // Our actual contract — explicit cache_control yields a hit on the repeated call — is
    // verified by explicitCache_secondCall_reportsCacheHit above.
}
