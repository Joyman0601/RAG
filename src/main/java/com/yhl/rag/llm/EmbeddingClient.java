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
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final HttpClient httpClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(LlmProperties llmProperties, ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.httpClient = buildHttpClient(llmProperties);
    }

    public List<Double> embed(String text) {
        long startNanos = System.nanoTime();

        if (!StringUtils.hasText(llmProperties.getEmbeddingBaseUrl())) {
            logFailure(startNanos, LlmErrorType.EMBEDDING_CONFIG_MISSING);
            throw new LlmException(
                    LlmErrorType.EMBEDDING_CONFIG_MISSING,
                    "llm.embedding-base-url 为空。当前文本 relay 不一定支持 embedding，请配置支持 /v1/embeddings 的 LLM_EMBEDDING_BASE_URL"
            );
        }

        if (!StringUtils.hasText(llmProperties.getEmbeddingApiKey())) {
            logFailure(startNanos, LlmErrorType.API_KEY_MISSING);
            throw new LlmException(
                    LlmErrorType.API_KEY_MISSING,
                    "llm.embedding-api-key 为空，请设置环境变量 LLM_EMBEDDING_API_KEY"
            );
        }

        EmbeddingRequest requestBody = new EmbeddingRequest(llmProperties.getEmbeddingModel(), text);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(llmProperties.getEmbeddingBaseUrl()) + "/embeddings"))
                    .timeout(Duration.ofSeconds(llmProperties.getEmbeddingTimeout()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.getEmbeddingApiKey())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmException(
                        LlmErrorType.HTTP_ERROR,
                        buildHttpErrorMessage(response.statusCode(), response.body())
                );
            }

            if (!StringUtils.hasText(response.body())) {
                throw new LlmException(LlmErrorType.EMPTY_RESPONSE_BODY, "Embedding 接口响应为空");
            }

            EmbeddingResponse embeddingResponse = objectMapper.readValue(response.body(), EmbeddingResponse.class);
            List<Double> vector = extractVector(embeddingResponse);
            logSuccess(startNanos, vector.size());
            return vector;
        } catch (LlmException exception) {
            logFailure(startNanos, exception.getErrorType());
            throw exception;
        } catch (IOException exception) {
            LlmErrorType errorType = isTimeout(exception) ? LlmErrorType.TIMEOUT : LlmErrorType.CLIENT_ERROR;
            logFailure(startNanos, errorType);
            throw new LlmException(errorType, buildIoErrorMessage(errorType, exception), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logFailure(startNanos, LlmErrorType.CLIENT_ERROR);
            throw new LlmException(LlmErrorType.CLIENT_ERROR, "Embedding 接口调用被中断", exception);
        }
    }

    private static List<Double> extractVector(EmbeddingResponse response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_RESPONSE_BODY, "Embedding 接口响应为空");
        }

        List<Double> vector = response.data().get(0).embedding();
        if (vector == null || vector.isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_EMBEDDING, "Embedding 向量为空");
        }

        return vector;
    }

    private static HttpClient buildHttpClient(LlmProperties llmProperties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(llmProperties.getEmbeddingTimeout()));

        if (StringUtils.hasText(llmProperties.getProxyHost()) && llmProperties.getProxyPort() > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(
                    llmProperties.getProxyHost(),
                    llmProperties.getProxyPort()
            )));
        }

        return builder.build();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }

        String normalizedBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        if (normalizedBaseUrl.endsWith("/v1")) {
            return normalizedBaseUrl;
        }

        return normalizedBaseUrl + "/v1";
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

    private static String buildIoErrorMessage(LlmErrorType errorType, IOException exception) {
        if (errorType == LlmErrorType.TIMEOUT) {
            return "Embedding 接口请求超时";
        }
        return "Embedding 接口调用失败：" + exception.getMessage();
    }

    private static String buildHttpErrorMessage(int statusCode, String responseBody) {
        if (statusCode == 404) {
            return "Embedding 接口不存在，HTTP 404。当前 llm.embedding-base-url 可能不支持 /v1/embeddings，"
                    + "请配置单独的 LLM_EMBEDDING_BASE_URL。响应内容：" + responseBody;
        }
        return "Embedding 接口返回非 2xx，HTTP " + statusCode + "，响应内容：" + responseBody;
    }

    private void logSuccess(long startNanos, int vectorDimension) {
        log.info("embedding_call model={} durationMs={} vectorDimension={} success=true",
                llmProperties.getEmbeddingModel(),
                elapsedMillis(startNanos),
                vectorDimension);
    }

    private void logFailure(long startNanos, LlmErrorType errorType) {
        log.warn("embedding_call model={} durationMs={} success=false errorType={}",
                llmProperties.getEmbeddingModel(),
                elapsedMillis(startNanos),
                errorType);
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    public record EmbeddingRequest(String model, String input) {
    }

    public record EmbeddingResponse(List<EmbeddingData> data) {
    }

    public record EmbeddingData(@JsonProperty("embedding") List<Double> embedding) {
    }
}
