package com.yhl.rag.llm;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.observability.MetricsService;
import com.yhl.rag.observability.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;

    public LlmClient(
            RestClient.Builder restClientBuilder,
            LlmProperties llmProperties,
            ObjectMapper objectMapper,
            MetricsService metricsService
    ) {
        this.llmProperties = llmProperties;
        this.httpClient = buildHttpClient(llmProperties);
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
        this.restClient = restClientBuilder
                .baseUrl(normalizeBaseUrl(llmProperties.getBaseUrl()))
                .requestFactory(buildRequestFactory(llmProperties, httpClient))
                .build();
    }

    public String generate(String instructions, List<LlmMessage> input) {
        return generateWithUsage(instructions, input).getAnswer();
    }

    public LlmGenerationResult generateWithUsage(String instructions, List<LlmMessage> input) {
        long startNanos = System.nanoTime();
        int inputChars = inputChars(input);

        validateInputLength(input);

        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            logFailure(startNanos, inputChars, 0, LlmErrorType.API_KEY_MISSING);
            throw new LlmException(
                    LlmErrorType.API_KEY_MISSING,
                    "llm.api-key 为空，请设置环境变量 LLM_API_KEY 或在 application.yml 中配置 llm.api-key"
            );
        }

        ResponsesRequest request = new ResponsesRequest(
                llmProperties.getModel(),
                instructions,
                input,
                llmProperties.getTemperature(),
                llmProperties.getMaxOutputTokens()
        );

        ResponsesResponse response;
        try {
            response = restClient.post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.getApiKey())
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                        String responseBody = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new LlmException(
                                LlmErrorType.HTTP_ERROR,
                                "模型接口返回非 2xx，HTTP " + httpResponse.getStatusCode().value()
                                        + "，响应内容：" + responseBody
                        );
                    })
                    .body(ResponsesResponse.class);
        } catch (LlmException exception) {
            logFailure(startNanos, inputChars, 0, exception.getErrorType());
            throw exception;
        } catch (RestClientException exception) {
            LlmErrorType errorType = isTimeout(exception) ? LlmErrorType.TIMEOUT : LlmErrorType.CLIENT_ERROR;
            logFailure(startNanos, inputChars, 0, errorType);
            throw new LlmException(errorType, buildClientErrorMessage(errorType, exception), exception);
        }

        String answer;
        try {
            answer = extractAnswer(response);
        } catch (LlmException exception) {
            logFailure(startNanos, inputChars, 0, exception.getErrorType());
            throw exception;
        }

        Integer promptTokens = promptTokens(response);
        Integer completionTokens = completionTokens(response);
        Integer totalTokens = totalTokens(response);
        logSuccess(startNanos, inputChars, charLength(answer), promptTokens, completionTokens, totalTokens);
        return new LlmGenerationResult(
                answer,
                promptTokens,
                completionTokens,
                totalTokens
        );
    }

    public void streamChat(String systemPrompt, String message, SseEmitter emitter) {
        long startNanos = System.nanoTime();
        int inputChars = charLength(message);
        AtomicInteger outputChars = new AtomicInteger();

        try {
            validateInputLength(message);
        } catch (LlmException exception) {
            logFailure(startNanos, inputChars, outputChars.get(), exception.getErrorType());
            emitter.completeWithError(exception);
            return;
        }

        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            LlmException exception = new LlmException(
                    LlmErrorType.API_KEY_MISSING,
                    "llm.api-key 为空，请设置环境变量 LLM_API_KEY 或在 application.yml 中配置 llm.api-key"
            );
            logFailure(startNanos, inputChars, outputChars.get(), exception.getErrorType());
            emitter.completeWithError(exception);
            return;
        }

        ResponsesStreamRequest requestBody = new ResponsesStreamRequest(
                llmProperties.getModel(),
                systemPrompt,
                List.of(new LlmMessage("user", message)),
                llmProperties.getTemperature(),
                llmProperties.getMaxOutputTokens(),
                true
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(normalizeBaseUrl(llmProperties.getBaseUrl()) + "/responses"))
                    .timeout(Duration.ofSeconds(llmProperties.getTimeoutSeconds()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.getApiKey())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmException(
                        LlmErrorType.HTTP_ERROR,
                        "模型接口返回非 2xx，HTTP " + response.statusCode() + "，响应内容：" + responseBody
                );
            }

            boolean sentAnyContent = streamResponseLines(response, emitter, outputChars);
            if (!sentAnyContent) {
                throw new LlmException(LlmErrorType.EMPTY_OUTPUT, "模型接口流式响应为空");
            }

            logSuccess(startNanos, inputChars, outputChars.get(), null, null, null);
            emitter.complete();
        } catch (LlmException exception) {
            logFailure(startNanos, inputChars, outputChars.get(), exception.getErrorType());
            emitter.completeWithError(exception);
        } catch (JsonProcessingException exception) {
            logFailure(startNanos, inputChars, outputChars.get(), LlmErrorType.INVALID_STRUCTURED_OUTPUT);
            emitter.completeWithError(new LlmException(
                    LlmErrorType.INVALID_STRUCTURED_OUTPUT,
                    "模型接口流式响应 JSON 解析失败",
                    exception
            ));
        } catch (IOException exception) {
            LlmErrorType errorType = isTimeout(exception) ? LlmErrorType.TIMEOUT : LlmErrorType.CLIENT_ERROR;
            logFailure(startNanos, inputChars, outputChars.get(), errorType);
            emitter.completeWithError(new LlmException(errorType, buildIoErrorMessage(errorType, exception), exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logFailure(startNanos, inputChars, outputChars.get(), LlmErrorType.CLIENT_ERROR);
            emitter.completeWithError(new LlmException(LlmErrorType.CLIENT_ERROR, "模型接口流式调用被中断", exception));
        } catch (IllegalStateException exception) {
            logFailure(startNanos, inputChars, outputChars.get(), LlmErrorType.CLIENT_ERROR);
            emitter.completeWithError(new LlmException(LlmErrorType.CLIENT_ERROR, "客户端已断开连接", exception));
        }
    }

    private boolean streamResponseLines(
            HttpResponse<java.io.InputStream> response,
            SseEmitter emitter,
            AtomicInteger outputChars
    ) throws IOException {
        boolean sentAnyContent = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }

                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    return sentAnyContent;
                }

                String content = extractStreamContent(data);
                if (content != null && !content.isEmpty()) {
                    sendStreamContent(emitter, content);
                    outputChars.addAndGet(charLength(content));
                    sentAnyContent = true;
                }
            }
        }

        return sentAnyContent;
    }

    private static void sendStreamContent(SseEmitter emitter, String content) throws IOException {
        try {
            emitter.send(content);
        } catch (IOException exception) {
            throw new LlmException(LlmErrorType.CLIENT_ERROR, "客户端已断开连接", exception);
        } catch (IllegalStateException exception) {
            throw new LlmException(LlmErrorType.CLIENT_ERROR, "客户端已断开连接", exception);
        }
    }

    private String extractStreamContent(String data) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(data);
        String type = textValue(root.get("type"));

        if ("response.output_text.delta".equals(type)) {
            return textValue(root.get("delta"));
        }

        String delta = textValue(root.get("delta"));
        if (delta != null) {
            return delta;
        }

        JsonNode outputText = root.get("output_text");
        if (outputText != null && outputText.isTextual()) {
            return outputText.asText();
        }

        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }

        JsonNode content = choices.get(0).path("delta").get("content");
        return textValue(content);
    }

    private static String textValue(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return node.asText();
    }

    private String extractAnswer(ResponsesResponse response) {
        if (response == null) {
            throw new LlmException(LlmErrorType.EMPTY_RESPONSE_BODY, "模型接口响应体为空");
        }

        if (StringUtils.hasText(response.outputText())) {
            return response.outputText();
        }

        if (response.output() == null || response.output().isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_OUTPUT, "模型接口响应 output 为空");
        }

        return response.output().stream()
                .filter(output -> output.content() != null)
                .flatMap(output -> output.content().stream())
                .filter(content -> StringUtils.hasText(content.text()))
                .map(ResponseContent::text)
                .findFirst()
                .orElseThrow(() -> new LlmException(
                        LlmErrorType.EMPTY_MESSAGE_CONTENT,
                        "模型接口响应 output[].content[].text 为空"
                ));
    }

    private static Integer promptTokens(ResponsesResponse response) {
        if (response == null || response.usage() == null) {
            return null;
        }
        if (response.usage().inputTokens() != null) {
            return response.usage().inputTokens();
        }
        return response.usage().promptTokens();
    }

    private static Integer completionTokens(ResponsesResponse response) {
        if (response == null || response.usage() == null) {
            return null;
        }
        if (response.usage().outputTokens() != null) {
            return response.usage().outputTokens();
        }
        return response.usage().completionTokens();
    }

    private static Integer totalTokens(ResponsesResponse response) {
        if (response == null || response.usage() == null) {
            return null;
        }
        return response.usage().totalTokens();
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

    private static JdkClientHttpRequestFactory buildRequestFactory(LlmProperties llmProperties, HttpClient httpClient) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(llmProperties.getTimeoutSeconds()));
        return requestFactory;
    }

    private static HttpClient buildHttpClient(LlmProperties llmProperties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(llmProperties.getTimeoutSeconds()));

        if (StringUtils.hasText(llmProperties.getProxyHost()) && llmProperties.getProxyPort() > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(
                    llmProperties.getProxyHost(),
                    llmProperties.getProxyPort()
            )));
        }

        return builder.build();
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

    private static String buildClientErrorMessage(LlmErrorType errorType, RestClientException exception) {
        if (errorType == LlmErrorType.TIMEOUT) {
            return "模型接口请求超时";
        }
        return "模型接口调用失败：" + exception.getMessage();
    }

    private static String buildIoErrorMessage(LlmErrorType errorType, IOException exception) {
        if (errorType == LlmErrorType.TIMEOUT) {
            return "模型接口流式请求超时";
        }
        return "模型接口流式调用失败：" + exception.getMessage();
    }

    private void validateInputLength(List<LlmMessage> input) {
        if (input == null) {
            return;
        }
        for (LlmMessage message : input) {
            if ("user".equals(message.role())) {
                validateInputLength(message.content());
            }
        }
    }

    private void validateInputLength(String text) {
        int currentLength = charLength(text);
        if (currentLength > llmProperties.getMaxInputChars()) {
            throw new LlmException(
                    LlmErrorType.INPUT_TOO_LONG,
                    "输入过长，当前长度 " + currentLength + " 个字符，最大允许 "
                            + llmProperties.getMaxInputChars() + " 个字符"
            );
        }
    }

    private static int inputChars(List<LlmMessage> input) {
        if (input == null) {
            return 0;
        }
        return input.stream()
                .map(LlmMessage::content)
                .mapToInt(LlmClient::charLength)
                .sum();
    }

    private static int charLength(String text) {
        return text == null ? 0 : text.length();
    }

    private void logSuccess(
            long startNanos,
            int inputChars,
            int outputChars,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens
    ) {
        log.info("llm_call requestId={} model={} promptTokens={} completionTokens={} totalTokens={} latencyMs={} inputChars={} outputChars={} success=true errorType={}",
                RequestContext.requestId(),
                llmProperties.getModel(),
                promptTokens,
                completionTokens,
                totalTokens,
                elapsedMillis(startNanos),
                inputChars,
                outputChars,
                null);
    }

    private void logFailure(long startNanos, int inputChars, int outputChars, LlmErrorType errorType) {
        metricsService.recordLlmCall(0, false);
        log.warn("llm_call requestId={} model={} promptTokens={} completionTokens={} totalTokens={} latencyMs={} inputChars={} outputChars={} success=false errorType={}",
                RequestContext.requestId(),
                llmProperties.getModel(),
                null,
                null,
                null,
                elapsedMillis(startNanos),
                inputChars,
                outputChars,
                errorType);
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private record ResponsesRequest(
            String model,
            String instructions,
            List<LlmMessage> input,
            double temperature,
            // This relay uses Responses API, so the limit field is max_output_tokens.
            // For chat/completions providers, the equivalent field is usually max_tokens.
            @JsonProperty("max_output_tokens") int maxOutputTokens
    ) {
    }

    private record ResponsesStreamRequest(
            String model,
            String instructions,
            List<LlmMessage> input,
            double temperature,
            // This relay uses Responses API, so the limit field is max_output_tokens.
            // For chat/completions providers, the equivalent field is usually max_tokens.
            @JsonProperty("max_output_tokens") int maxOutputTokens,
            boolean stream
    ) {
    }

    private record ResponsesResponse(
            @JsonProperty("output_text") String outputText,
            List<ResponseOutput> output,
            ResponseUsage usage
    ) {
    }

    private record ResponseOutput(List<ResponseContent> content) {
    }

    private record ResponseContent(String type, String text) {
    }

    private record ResponseUsage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens
    ) {
    }
}
