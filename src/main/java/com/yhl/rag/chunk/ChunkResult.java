package com.yhl.rag.chunk;

import java.util.List;

import com.yhl.rag.document.DocumentChunk;

/**
 * 分块产物：待 embedding 入库的子块 + 需写 ParentStore 的父块。
 * FIXED / SEMANTIC 策略 parents 为空；MARKDOWN 才产出父块。
 */
public record ChunkResult(List<DocumentChunk> children, List<ParentBlock> parents) {
    public ChunkResult {
        children = children == null ? List.of() : List.copyOf(children);
        parents = parents == null ? List.of() : List.copyOf(parents);
    }

    public static ChunkResult childrenOnly(List<DocumentChunk> children) {
        return new ChunkResult(children, List.of());
    }
}
