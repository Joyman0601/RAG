package com.yhl.rag.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class LangfuseTracerTest {

    private LangfuseClient client;
    private LangfuseProperties properties;
    private LangfuseTracer tracer;

    @BeforeEach
    void setUp() {
        client = mock(LangfuseClient.class);
        properties = new LangfuseProperties();
        tracer = new LangfuseTracer(client, properties);
    }

    @Test
    void disabled_noInteractionWithClient() {
        properties.setEnabled(false);

        tracer.recordGeneration("trace-1", "rag_ask", "gpt-4", "prompt", "answer", 10, 5, 100);

        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabled_sendsBatchWithTraceAndGeneration() {
        properties.setEnabled(true);

        tracer.recordGeneration("trace-1", "rag_ask", "gpt-4", "prompt text", "answer text", 10, 5, 100);

        verify(client, times(1)).sendBatchAsync(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabled_batchContainsTwoEvents() {
        properties.setEnabled(true);

        // Capture the batch for inspection
        final List<Map<String, Object>>[] captured = new List[1];
        doAnswer(inv -> { captured[0] = inv.getArgument(0); return null; })
                .when(client).sendBatchAsync(anyList());

        tracer.recordGeneration("trace-42", "rag_ask", "gpt-4o", "input", "output", 20, 8, 200);

        assertThat(captured[0]).hasSize(2);
        assertThat(captured[0]).extracting(e -> e.get("type"))
                .containsExactlyInAnyOrder("trace-create", "generation-create");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabled_nullTraceId_stillSendsBatch() {
        properties.setEnabled(true);

        // null traceId should auto-generate one instead of NPE
        tracer.recordGeneration(null, "rag_ask", "gpt-4", "prompt", "answer", 5, 3, 50);

        verify(client, times(1)).sendBatchAsync(anyList());
    }
}
