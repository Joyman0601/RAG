package com.yhl.rag.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LangfuseClientTest {

    private MockRestServiceServer mockServer;
    private LangfuseClient client;
    private LangfuseProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setHost("http://langfuse-test");
        properties.setPublicKey("pk-test");
        properties.setSecretKey("sk-test");

        RestClient.Builder builder = RestClient.builder().baseUrl("http://langfuse-test");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        // package-private 构造器：直接接受已有 mock intercept 的 RestClient
        RestClient mockRestClient = builder.build();
        client = new LangfuseClient(properties, mockRestClient, new ObjectMapper());
    }

    @Test
    void sendsBatchToCorrectEndpoint() throws InterruptedException {
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("pk-test:sk-test".getBytes());
        mockServer.expect(requestTo("http://langfuse-test/api/public/ingestion"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", expectedAuth))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess());

        client.sendBatchAsync(List.of(Map.of("type", "trace-create", "id", "t1")));

        // 等待 virtual thread 执行完成
        Thread.sleep(200);
        mockServer.verify();
    }

    @Test
    void serverError_doesNotThrow() throws InterruptedException {
        mockServer.expect(requestTo("http://langfuse-test/api/public/ingestion"))
                .andRespond(withServerError());

        assertThatNoException().isThrownBy(() -> {
            client.sendBatchAsync(List.of(Map.of("type", "trace-create", "id", "t1")));
            Thread.sleep(200);
        });
    }

    @Test
    void disabled_doesNotSendRequest() {
        properties.setEnabled(false);

        client.sendBatchAsync(List.of(Map.of("type", "trace-create", "id", "t1")));

        // mockServer.verify() will pass without any requests
        mockServer.verify();
    }
}
