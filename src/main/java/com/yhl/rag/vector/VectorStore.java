package com.yhl.rag.vector;

import java.util.List;
import java.util.Map;

import com.yhl.rag.document.DocumentChunk;

public interface VectorStore {

    void save(DocumentChunk chunk, List<Double> embedding);

    default void saveAll(Map<DocumentChunk, List<Double>> embeddings) {
        embeddings.forEach(this::save);
    }

    List<VectorSearchResult> search(VectorSearchRequest request);

    void deleteByDocumentId(String documentId);

    void deleteByChunkIds(List<String> chunkIds);

    List<Double> getEmbedding(String chunkId);

    Map<String, List<Double>> getEmbeddingSnapshot();
}
