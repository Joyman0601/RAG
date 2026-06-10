package com.yhl.rag.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 需要真实 Langfuse 实例运行时才执行（设置 LANGFUSE_ENABLED=true + LANGFUSE_HOST/PUBLIC_KEY/SECRET_KEY）。
 * 验证 LangfuseTracer -> LangfuseClient -> 真实 Langfuse 端到端通路。
 * 跑完后去 http://192.168.99.75:3000 UI 搜 trace name "rag_ask_integration_test" 确认可见。
 */
@EnabledIfEnvironmentVariable(named = "LANGFUSE_ENABLED", matches = "true")
class LangfuseIntegrationTest {

    @Test
    void sendRealTraceToLangfuse() {
        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(true);
        props.setHost(System.getenv().getOrDefault("LANGFUSE_HOST", "http://192.168.99.75:3000"));
        props.setPublicKey(System.getenv().getOrDefault("LANGFUSE_PUBLIC_KEY", ""));
        props.setSecretKey(System.getenv().getOrDefault("LANGFUSE_SECRET_KEY", ""));

        LangfuseClient client = new LangfuseClient(props, RestClient.builder(), new ObjectMapper());
        LangfuseTracer tracer = new LangfuseTracer(client, props);

        assertThatNoException().isThrownBy(() -> {
            tracer.recordGeneration(
                    "integration-trace-001",
                    "rag_ask_integration_test",
                    "gpt-5.5",
                    "[system] 你是 RAG 助手\n[user] 请假需要什么材料",
                    "请假需要填写请假申请表，并提交给直属主管审批。",
                    128, 20, 350
            );
            // 等异步线程发送完成
            Thread.sleep(500);
        });
    }
}
