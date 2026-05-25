package com.yhl.rag.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentStatus;
import com.yhl.rag.document.DocumentVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryVectorStoreTest {

    private InMemoryVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
    }

    @Test
    void search_whenChunksMatch_returnsTopKByScore() {
        vectorStore.save(chunk("c1", "doc1", "refund policy", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.save(chunk("c2", "doc2", "travel policy", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(0.0, 1.0));

        List<VectorSearchResult> results = vectorStore.search(request(List.of(1.0, 0.0), 1, 0.1, "user_001", "dept-a"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunk().getChunkId()).isEqualTo("c1");
        assertThat(results.get(0).isIncluded()).isTrue();
    }

    @Test
    void search_whenUserCannotAccessChunk_filtersBeforeScoring() {
        vectorStore.save(chunk("private-other", "doc1", "private content", DocumentVisibility.PRIVATE, "user_002", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.save(chunk("internal-other-dept", "doc2", "internal content", DocumentVisibility.INTERNAL, "user_002", "dept-b", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.save(chunk("public", "doc3", "public content", DocumentVisibility.PUBLIC, "user_002", "dept-b", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));

        List<VectorSearchResult> results = vectorStore.search(request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a"));

        assertThat(results)
                .extracting(result -> result.getChunk().getChunkId())
                .containsExactly("public");
    }

    @Test
    void search_whenOldVersionEmbeddingIsDeleted_doesNotRecallOldVersion() {
        vectorStore.save(chunk("old", "doc1", "old content", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.deleteByChunkIds(List.of("old"));
        vectorStore.save(chunk("new", "doc1", "new content", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 2), List.of(0.9, 0.1));

        List<VectorSearchResult> results = vectorStore.search(request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a"));

        assertThat(results)
                .extracting(result -> result.getChunk().getChunkId())
                .containsExactly("new");
    }

    @Test
    void search_whenDocumentVectorsAreDeleted_doesNotRecallDeletedDocument() {
        vectorStore.save(chunk("c1", "doc1", "deleted document content", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.deleteByDocumentId("doc1");

        List<VectorSearchResult> results = vectorStore.search(request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a"));

        assertThat(results).isEmpty();
    }

    @Test
    void search_whenScoreIsBelowThreshold_excludesUnlessDebugRequested() {
        vectorStore.save(chunk("low-score", "doc1", "orthogonal content", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(0.0, 1.0));
        VectorSearchRequest request = request(List.of(1.0, 0.0), 10, 0.5, "user_001", "dept-a");

        assertThat(vectorStore.search(request)).isEmpty();

        request.setIncludeBelowThreshold(true);
        List<VectorSearchResult> debugResults = vectorStore.search(request);
        assertThat(debugResults).hasSize(1);
        assertThat(debugResults.get(0).isIncluded()).isFalse();
        assertThat(debugResults.get(0).getDebugInfo()).isEqualTo("score < threshold");
    }

    private static VectorSearchRequest request(List<Double> queryVector, int topK, double scoreThreshold, String userId, String department) {
        VectorSearchRequest request = new VectorSearchRequest();
        request.setQueryVector(queryVector);
        request.setTopK(topK);
        request.setScoreThreshold(scoreThreshold);
        request.setUserId(userId);
        request.setDepartment(department);
        request.setStatus(DocumentStatus.ACTIVE);
        return request;
    }

    private static DocumentChunk chunk(
            String chunkId,
            String documentId,
            String content,
            DocumentVisibility visibility,
            String ownerId,
            String department,
            DocumentStatus status,
            int version
    ) {
        return new DocumentChunk(
                chunkId,
                documentId,
                documentId + ".md",
                content,
                "hash-" + chunkId,
                0,
                Instant.now(),
                status,
                version,
                ownerId,
                department,
                visibility,
                1
        );
    }
}
