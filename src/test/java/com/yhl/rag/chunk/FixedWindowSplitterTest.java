package com.yhl.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentException;
import com.yhl.rag.document.DocumentVisibility;
import org.junit.jupiter.api.Test;

class FixedWindowSplitterTest {

    private final FixedWindowSplitter splitter = new FixedWindowSplitter();

    @Test
    void split_fixedWindowWithOverlap_matchesLegacyBehavior() {
        ChunkConfig config = config(4, 1);

        ChunkResult result = splitter.split("doc-1", "a.txt", "abcdefghij", config);

        assertThat(result.children()).extracting(DocumentChunk::getContent)
                .containsExactly("abcd", "defg", "ghij");
        assertThat(result.children()).allSatisfy(chunk -> assertThat(chunk.getParentId()).isNull());
        assertThat(result.parents()).isEmpty();
    }

    @Test
    void split_blankText_returnsNoChunks() {
        assertThat(splitter.split("doc-1", "a.txt", "   ", config(10, 0)).children()).isEmpty();
    }

    @Test
    void split_invalidConfig_throwsSameErrorCode() {
        assertThatThrownBy(() -> splitter.split("doc-1", "a.txt", "abc", config(0, 0)))
                .isInstanceOf(DocumentException.class)
                .satisfies(ex -> assertThat(((DocumentException) ex).getErrorType()).isEqualTo("DOCUMENT_INVALID_CHUNK_CONFIG"));
    }

    @Test
    void split_stableChunkId_isDeterministic() {
        ChunkConfig config = config(4, 1);

        List<DocumentChunk> first = splitter.split("doc-1", "a.txt", "abcdefghij", config).children();
        List<DocumentChunk> second = splitter.split("doc-1", "a.txt", "abcdefghij", config).children();

        assertThat(first).extracting(DocumentChunk::getChunkId)
                .isEqualTo(second.stream().map(DocumentChunk::getChunkId).toList());
    }

    private static ChunkConfig config(int chunkSize, int overlap) {
        return new ChunkConfig(
                ChunkStrategy.FIXED,
                chunkSize,
                overlap,
                0.6,
                1,
                "tenant-default",
                "owner-1",
                "dept-1",
                DocumentVisibility.DEPARTMENT,
                Set.of(),
                Set.of(),
                0
        );
    }
}
