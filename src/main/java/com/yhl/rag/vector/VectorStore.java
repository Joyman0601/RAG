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

    /**
     * BM25 关键词检索：在通过权限/版本过滤的 chunk 上做字面匹配召回。
     * 与稠密向量检索互补，擅长精确关键词、编号、专名。queryText 为原始问题文本，
     * request 仅复用其过滤条件与 topK（queryVector 可为空，不参与）。
     */
    default List<VectorSearchResult> keywordSearch(String queryText, VectorSearchRequest request) {
        return List.of();
    }

    void deleteByDocumentId(String documentId);

    void deleteByChunkIds(List<String> chunkIds);

    List<Double> getEmbedding(String chunkId);

    Map<String, List<Double>> getEmbeddingSnapshot();
}
