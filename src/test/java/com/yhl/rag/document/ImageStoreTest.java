package com.yhl.rag.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageStoreTest {

    @Test
    void putThenGet_returnsSameBytesAndMime() {
        ImageStore store = new ImageStore();
        byte[] bytes = {9, 8, 7};

        String ref = store.put(bytes, "image/png");

        assertThat(ref).isNotBlank();
        assertThat(store.get(ref)).hasValueSatisfying(stored -> {
            assertThat(stored.bytes()).containsExactly(9, 8, 7);
            assertThat(stored.mimeType()).isEqualTo("image/png");
        });
    }

    @Test
    void get_missingRef_returnsEmpty() {
        ImageStore store = new ImageStore();

        assertThat(store.get("nope")).isEmpty();
        assertThat(store.get(null)).isEmpty();
    }
}
