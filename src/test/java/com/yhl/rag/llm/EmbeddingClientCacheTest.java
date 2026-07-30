package com.yhl.rag.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbeddingClientCacheTest {

    private HttpServer server;

    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() throws IOException {
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", this::handleEmbedding);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embed_whenSameModelAndText_reusesCachedVectorWithoutCallingApiAgain() {
        EmbeddingCache cache = new EmbeddingCache();
        EmbeddingClient client = new EmbeddingClient(properties("model-a"), new ObjectMapper(), cache);

        List<Double> first = client.embed("同一段文本");
        List<Double> second = client.embed("同一段文本");

        assertThat(first).containsExactly(1.0, 0.0);
        assertThat(second).containsExactly(1.0, 0.0);
        assertThat(requestCount).hasValue(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void embed_whenEmbeddingModelIsDifferent_doesNotShareCacheEntry() {
        EmbeddingCache cache = new EmbeddingCache();
        EmbeddingClient firstClient = new EmbeddingClient(properties("model-a"), new ObjectMapper(), cache);
        EmbeddingClient secondClient = new EmbeddingClient(properties("model-b"), new ObjectMapper(), cache);

        firstClient.embed("同一段文本");
        secondClient.embed("同一段文本");

        assertThat(requestCount).hasValue(2);
        assertThat(cache.size()).isEqualTo(2);
    }

    private LlmProperties properties(String model) {
        LlmProperties properties = new LlmProperties();
        properties.setEmbeddingBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setEmbeddingApiKey("test-key");
        properties.setEmbeddingModel(model);
        properties.setEmbeddingTimeout(5);
        return properties;
    }

    private void handleEmbedding(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        byte[] response = """
                {"data":[{"embedding":[1.0,0.0]}]}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(response);
        }
    }
}
