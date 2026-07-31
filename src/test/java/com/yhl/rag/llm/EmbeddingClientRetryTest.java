package com.yhl.rag.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 守 sendWithOneRetry 的两条关键契约：
 * 1. 首次成功不重试（不能因加重试导致每次都发两遍）；
 * 2. HTTP 4xx/5xx 不重试（重试只会放大服务器压力和计费）。
 *
 * "Connection reset 后重试成功"这类真实瞬时错误行为跨 JDK/平台不好稳定模拟，
 * 靠代码 review + 生产日志的 "transient IOException, retrying once" WARN 验证。
 */
class EmbeddingClientRetryTest {

    private HttpServer server;
    private AtomicInteger requestCount;
    private volatile int httpStatusCode;

    @BeforeEach
    void setUp() throws IOException {
        requestCount = new AtomicInteger();
        httpStatusCode = 200;
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void firstCallSucceeds_doesNotRetry() {
        EmbeddingClient client = new EmbeddingClient(properties(), new ObjectMapper(), new EmbeddingCache());

        client.embed("hello");

        assertThat(requestCount).hasValue(1);
    }

    @Test
    void http5xx_doesNotRetry() {
        httpStatusCode = 503;
        EmbeddingClient client = new EmbeddingClient(properties(), new ObjectMapper(), new EmbeddingCache());

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(LlmException.class)
                .satisfies(ex -> assertThat(((LlmException) ex).getErrorType())
                        .isEqualTo(LlmErrorType.HTTP_ERROR));
        assertThat(requestCount).hasValue(1);
    }

    private LlmProperties properties() {
        LlmProperties properties = new LlmProperties();
        properties.setEmbeddingBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setEmbeddingApiKey("test-key");
        properties.setEmbeddingModel("test-model");
        properties.setEmbeddingTimeout(5);
        return properties;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        byte[] response;
        if (httpStatusCode >= 200 && httpStatusCode < 300) {
            response = "{\"data\":[{\"embedding\":[1.0,0.0]}]}".getBytes(StandardCharsets.UTF_8);
        } else {
            response = ("server error " + httpStatusCode).getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(httpStatusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
}
