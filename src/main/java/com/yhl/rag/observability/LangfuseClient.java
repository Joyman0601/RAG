package com.yhl.rag.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 Langfuse ingestion 客户端。
 * 打 POST /api/public/ingestion，Basic Auth，异步 fire-and-forget，失败静默吞掉。
 */
@Component
public class LangfuseClient {

    private static final Logger log = LoggerFactory.getLogger(LangfuseClient.class);

    private final LangfuseProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Autowired
    public LangfuseClient(LangfuseProperties properties,
                          RestClient.Builder restClientBuilder,
                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl(properties.getHost())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    /**
     * 测试专用构造器：直接传入已配置好的 RestClient（如 MockRestServiceServer 绑定的）。
     */
    LangfuseClient(LangfuseProperties properties, RestClient restClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    /**
     * 异步发送一批 ingestion 事件，失败不抛不阻塞。
     */
    public void sendBatchAsync(List<Map<String, Object>> batch) {
        if (!properties.isEnabled() || batch == null || batch.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                Map<String, Object> body = Map.of("batch", batch);
                String json = objectMapper.writeValueAsString(body);
                String auth = Base64.getEncoder().encodeToString(
                        (properties.getPublicKey() + ":" + properties.getSecretKey())
                                .getBytes(StandardCharsets.UTF_8));
                restClient.post()
                        .uri("/api/public/ingestion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Basic " + auth)
                        .header("Connection", "close")
                        .body(json)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("langfuse_send_failed message={}", e.getMessage());
            }
        });
    }
}
