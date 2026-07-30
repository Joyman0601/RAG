package com.yhl.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentVisibility;
import com.yhl.rag.llm.EmbeddingClient;
import org.junit.jupiter.api.Test;

class SemanticSplitterTest {

    @Test
    void split_breaksWhereAdjacentSimilarityDropsBelowThreshold() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed("句子甲。")).thenReturn(List.of(1.0, 0.0));
        when(embeddingClient.embed("句子乙。")).thenReturn(List.of(1.0, 0.05));
        when(embeddingClient.embed("句子丙。")).thenReturn(List.of(0.0, 1.0));
        when(embeddingClient.embed("句子丁。")).thenReturn(List.of(0.0, 1.0));
        SemanticSplitter splitter = new SemanticSplitter(embeddingClient);

        ChunkResult result = splitter.split("doc-1", "a.txt", "句子甲。句子乙。句子丙。句子丁。", config(0.6));

        assertThat(result.children()).extracting(DocumentChunk::getContent)
                .containsExactly("句子甲。句子乙。", "句子丙。句子丁。");
        assertThat(result.children()).allSatisfy(chunk -> assertThat(chunk.getParentId()).isNull());
        assertThat(result.parents()).isEmpty();
    }

    @Test
    void split_singleSentence_returnsOneChunk() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed("只有一句。")).thenReturn(List.of(1.0, 0.0));
        SemanticSplitter splitter = new SemanticSplitter(embeddingClient);

        ChunkResult result = splitter.split("doc-1", "a.txt", "只有一句。", config(0.6));

        assertThat(result.children()).extracting(DocumentChunk::getContent).containsExactly("只有一句。");
    }

    private static ChunkConfig config(double threshold) {
        return new ChunkConfig(
                ChunkStrategy.SEMANTIC,
                600,
                100,
                threshold,
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
