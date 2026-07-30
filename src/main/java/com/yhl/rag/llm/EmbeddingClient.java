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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final HttpClient httpClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final EmbeddingCache embeddingCache;

    @Autowired
    public EmbeddingClient(LlmProperties llmProperties, ObjectMapper objectMapper, EmbeddingCache embeddingCache) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.embeddingCache = embeddingCache;
        this.httpClient = buildHttpClient(llmProperties);
    }

    public EmbeddingClient(LlmProperties llmProperties, ObjectMapper objectMapper) {
        this(llmProperties, objectMapper, new EmbeddingCache());
    }

    /**
     * 图像 embedding：把图片字节编码为 data URL 作为 input 投给同一 /v1/embeddings 端点。
     * VL embedding 模型（Qwen3-VL-Embedding）据此对图像本身打向量，与文本进**同一向量空间**，
     * 文本 query 因此可直接召回图像 chunk——真多模态，而非"图转文再 embedding"。
     */
    public List<Double> embedImage(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new LlmException(LlmErrorType.EMPTY_EMBEDDING, "图像字节为空，无法 embedding");
        }
        String dataUrl = toImageDataUrl(imageBytes, mimeType);
        if (dashscopeMultimodalStyle()) {
            // DashScope 原生多模态：图片作为 input.contents:[{image:dataURL}] 投给 VL 模型。
            return embedDashscope(true, dataUrl);
        }
        // OpenAI 兼容：图片 dataURL 直接作为 input 字符串，复用文本同一条 /embeddings 路径。
        return embed(dataUrl);
    }

    /** 拼 OpenAI 兼容的图片 data URL：data:&lt;mime&gt;;base64,&lt;...&gt;。 */
    public static String toImageDataUrl(byte[] imageBytes, String mimeType) {
        String mime = StringUtils.hasText(mimeType) ? mimeType : "application/octet-stream";
        return "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(imageBytes);
    }

    public List<Double> embed(String text) {
        if (dashscopeMultimodalStyle()) {
            // DashScope 原生多模态：文本与图像走同一 VL 模型进同一向量空间。
            return embedDashscope(false, text);
        }
        long startNanos = System.nanoTime();
        String embeddingModel = llmProperties.getEmbeddingModel();
        String textHash = embeddingCache.textHash(text);
        var cachedEmbedding = embeddingCache.get(embeddingModel, text);
        if (cachedEmbedding.isPresent()) {
            List<Double> vector = cachedEmbedding.get().getVector();
            log.info("embedding_cache_hit model={} textHash={} tokenCount={} vectorDimension={}",
                    embeddingModel,
                    textHash,
                    cachedEmbedding.get().getTokenCount(),
                    vector.size());
            return vector;
        }
        log.info("embedding_cache_miss model={} textHash={}", embeddingModel, textHash);

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

        EmbeddingRequest requestBody = new EmbeddingRequest(embeddingModel, text);

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
            embeddingCache.put(embeddingModel, text, vector);
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

    private boolean dashscopeMultimodalStyle() {
        return "dashscope-multimodal".equalsIgnoreCase(llmProperties.getEmbeddingStyle());
    }

    /**
     * DashScope 原生多模态 embedding：文本/图片统一作为 input.contents 项投给 VL 模型，
     * 取 output.embeddings[0].embedding。embedding-base-url 直接用作完整端点 URL（不追加 /embeddings）。
     */
    private List<Double> embedDashscope(boolean isImage, String content) {
        long startNanos = System.nanoTime();
        String model = llmProperties.getEmbeddingModel();
        String contentHash = embeddingCache.textHash(content);
        var cached = embeddingCache.get(model, content);
        if (cached.isPresent()) {
            List<Double> vector = cached.get().getVector();
            log.info("embedding_cache_hit model={} textHash={} tokenCount={} vectorDimension={} modality={}",
                    model, contentHash, cached.get().getTokenCount(), vector.size(), isImage ? "IMAGE" : "TEXT");
            return vector;
        }
        log.info("embedding_cache_miss model={} textHash={} modality={}", model, contentHash, isImage ? "IMAGE" : "TEXT");

        if (!StringUtils.hasText(llmProperties.getEmbeddingBaseUrl())) {
            logFailure(startNanos, LlmErrorType.EMBEDDING_CONFIG_MISSING);
            throw new LlmException(LlmErrorType.EMBEDDING_CONFIG_MISSING,
                    "llm.embedding-base-url 为空。dashscope-multimodal 风格需配置完整端点 URL（multimodal-embedding）");
        }
        if (!StringUtils.hasText(llmProperties.getEmbeddingApiKey())) {
            logFailure(startNanos, LlmErrorType.API_KEY_MISSING);
            throw new LlmException(LlmErrorType.API_KEY_MISSING, "llm.embedding-api-key 为空，请设置 LLM_EMBEDDING_API_KEY");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(llmProperties.getEmbeddingBaseUrl()))
                    .timeout(Duration.ofSeconds(llmProperties.getEmbeddingTimeout()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.getEmbeddingApiKey())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(buildDashScopeBody(model, isImage, content)),
                            StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmException(LlmErrorType.HTTP_ERROR,
                        "DashScope embedding 返回非 2xx，HTTP " + response.statusCode() + "，响应内容：" + response.body());
            }
            if (!StringUtils.hasText(response.body())) {
                throw new LlmException(LlmErrorType.EMPTY_RESPONSE_BODY, "DashScope embedding 响应为空");
            }
            DashScopeResponse parsed = objectMapper.readValue(response.body(), DashScopeResponse.class);
            List<Double> vector = extractDashScopeVector(parsed);
            embeddingCache.put(model, content, vector);
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
            throw new LlmException(LlmErrorType.CLIENT_ERROR, "DashScope embedding 调用被中断", exception);
        }
    }

    /** 构造 DashScope 多模态请求体：{model, input:{contents:[{text|image: content}]}}。 */
    static Map<String, Object> buildDashScopeBody(String model, boolean isImage, String content) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put(isImage ? "image" : "text", content);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("contents", List.of(item));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        return body;
    }

    private static List<Double> extractDashScopeVector(DashScopeResponse response) {
        if (response == null || response.output() == null
                || response.output().embeddings() == null || response.output().embeddings().isEmpty()) {
            String detail = response == null ? "null" : (response.code() + ":" + response.message());
            throw new LlmException(LlmErrorType.EMPTY_RESPONSE_BODY, "DashScope embedding 响应无 embeddings（" + detail + "）");
        }
        List<Double> vector = response.output().embeddings().get(0).embedding();
        if (vector == null || vector.isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_EMBEDDING, "DashScope embedding 向量为空");
        }
        return vector;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingResponse(List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingData(@JsonProperty("embedding") List<Double> embedding) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DashScopeResponse(DashScopeOutput output, String code, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DashScopeOutput(List<DashScopeEmbedding> embeddings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DashScopeEmbedding(
            @JsonProperty("embedding") List<Double> embedding,
            @JsonProperty("type") String type,
            @JsonProperty("index") Integer index
    ) {
    }
}
