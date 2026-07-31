package com.yhl.rag.llm;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Cross-Encoder 精排客户端：调用 bge-reranker 类 rerank API（默认硅基流动 BAAI/bge-reranker-v2-m3）。
 * 与 Bi-Encoder 召回不同，这里把 query 与每篇候选文档一起送入模型做逐对交互打分，精度更高、只用于少量候选。
 * 请求/响应遵循通用 /rerank 契约（query + documents + top_n → results[].index + relevance_score），
 * 兼容硅基流动 / Jina / Cohere v2。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final HttpClient httpClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    @Autowired
    public RerankClient(LlmProperties llmProperties, ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.httpClient = buildHttpClient(llmProperties);
    }

    public boolean isConfigured() {
        return StringUtils.hasText(llmProperties.getRerankBaseUrl())
                && StringUtils.hasText(llmProperties.getRerankApiKey());
    }

    /**
     * 对候选文档按与 query 的相关性重排，返回原 documents 列表中的下标顺序（最相关在前），长度不超过 topN。
     */
    public List<Integer> rerank(String query, List<String> documents, int topN) {
        if (!StringUtils.hasText(query) || documents == null || documents.isEmpty()) {
            return List.of();
        }
        if (!isConfigured()) {
            throw new LlmException(
                    LlmErrorType.EMBEDDING_CONFIG_MISSING,
                    "rerank 未配置：请设置 LLM_RERANK_BASE_URL 与 LLM_RERANK_API_KEY"
            );
        }

        long startNanos = System.nanoTime();
        int effectiveTopN = Math.min(topN <= 0 ? documents.size() : topN, documents.size());
        RerankRequest requestBody = new RerankRequest(
                llmProperties.getRerankModel(), query, documents, effectiveTopN);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(llmProperties.getRerankBaseUrl()) + "/rerank"))
                    .timeout(Duration.ofSeconds(llmProperties.getRerankTimeout()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.getRerankApiKey())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sendWithOneRetry(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmException(LlmErrorType.HTTP_ERROR,
                        "Rerank 接口返回非 2xx，HTTP " + response.statusCode() + "，响应内容：" + response.body());
            }
            if (!StringUtils.hasText(response.body())) {
                throw new LlmException(LlmErrorType.EMPTY_RESPONSE_BODY, "Rerank 接口响应为空");
            }

            RerankResponse rerankResponse = objectMapper.readValue(response.body(), RerankResponse.class);
            List<Integer> ordered = extractOrderedIndexes(rerankResponse, documents.size());
            log.info("rerank_call model={} durationMs={} candidateCount={} returnedCount={} success=true",
                    llmProperties.getRerankModel(), elapsedMillis(startNanos), documents.size(), ordered.size());
            return ordered;
        } catch (LlmException exception) {
            log.warn("rerank_call model={} durationMs={} success=false errorType={}",
                    llmProperties.getRerankModel(), elapsedMillis(startNanos), exception.getErrorType());
            throw exception;
        } catch (IOException exception) {
            LlmErrorType errorType = isTimeout(exception) ? LlmErrorType.TIMEOUT : LlmErrorType.CLIENT_ERROR;
            log.warn("rerank_call model={} durationMs={} success=false errorType={}",
                    llmProperties.getRerankModel(), elapsedMillis(startNanos), errorType);
            throw new LlmException(errorType, "Rerank 接口调用失败：" + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmErrorType.CLIENT_ERROR, "Rerank 接口调用被中断", exception);
        }
    }

    private static List<Integer> extractOrderedIndexes(RerankResponse response, int documentCount) {
        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_RESPONSE_BODY, "Rerank 接口结果为空");
        }
        return response.results().stream()
                .filter(result -> result.index() >= 0 && result.index() < documentCount)
                .sorted(Comparator.comparingDouble(RerankResult::relevanceScore).reversed())
                .map(RerankResult::index)
                .toList();
    }

    /**
     * 对瞬时网络错误（Connection reset 等非 Timeout 的 IOException）重试一次，
     * 中间等待 300ms。Timeout / HTTP 4xx-5xx 都不重试。
     */
    private HttpResponse<String> sendWithOneRetry(HttpRequest request)
            throws IOException, InterruptedException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException first) {
            if (isTimeout(first)) {
                throw first;
            }
            log.warn("rerank transient IOException, retrying once: {}", first.toString());
            try {
                Thread.sleep(300L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static HttpClient buildHttpClient(LlmProperties llmProperties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(llmProperties.getRerankTimeout()));
        if (StringUtils.hasText(llmProperties.getProxyHost()) && llmProperties.getProxyPort() > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(
                    llmProperties.getProxyHost(), llmProperties.getProxyPort())));
        }
        return builder.build();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.http.HttpConnectTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    public record RerankRequest(String model, String query, List<String> documents,
                                @JsonProperty("top_n") int topN) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankResponse(List<RerankResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankResult(int index, @JsonProperty("relevance_score") double relevanceScore) {
    }
}
