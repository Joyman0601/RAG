package com.yhl.rag.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EmbeddingClientImageTest {

    @Test
    void toImageDataUrl_buildsBase64DataUrlWithMime() {
        byte[] bytes = "PNGDATA".getBytes(StandardCharsets.UTF_8);

        String dataUrl = EmbeddingClient.toImageDataUrl(bytes, "image/png");

        String expectedBase64 = Base64.getEncoder().encodeToString(bytes);
        assertThat(dataUrl).isEqualTo("data:image/png;base64," + expectedBase64);
    }

    @Test
    void toImageDataUrl_defaultsMimeWhenBlank() {
        byte[] bytes = {1, 2, 3};

        String dataUrl = EmbeddingClient.toImageDataUrl(bytes, null);

        assertThat(dataUrl).startsWith("data:application/octet-stream;base64,");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildDashScopeBody_textPutsTextContent() {
        Map<String, Object> body = EmbeddingClient.buildDashScopeBody("qwen3-vl-embedding", false, "年假申请");

        assertThat(body.get("model")).isEqualTo("qwen3-vl-embedding");
        Map<String, Object> input = (Map<String, Object>) body.get("input");
        List<Map<String, String>> contents = (List<Map<String, String>>) input.get("contents");
        assertThat(contents).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("text", "年假申请");
            assertThat(item).doesNotContainKey("image");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildDashScopeBody_imagePutsImageContent() {
        Map<String, Object> body = EmbeddingClient.buildDashScopeBody(
                "qwen3-vl-embedding", true, "data:image/png;base64,AAAA");

        Map<String, Object> input = (Map<String, Object>) body.get("input");
        List<Map<String, String>> contents = (List<Map<String, String>>) input.get("contents");
        assertThat(contents).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("image", "data:image/png;base64,AAAA");
            assertThat(item).doesNotContainKey("text");
        });
    }
}
