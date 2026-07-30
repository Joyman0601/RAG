package com.yhl.rag.chunk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.yhl.rag.document.DocumentChunk;
import org.springframework.stereotype.Component;

/**
 * 固定窗口 + overlap 滑窗：原 DocumentService.chunkText 行为，逐字符切，无父块。
 * 默认策略，保证存量行为零回归。
 */
@Component
public class FixedWindowSplitter implements TextSplitter {

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.FIXED;
    }

    @Override
    public ChunkResult split(String documentId, String filename, String text, ChunkConfig config) {
        Chunks.validateConfig(config);
        return ChunkResult.childrenOnly(slice(documentId, filename, text, null, config));
    }

    /** 把一段文本按固定窗口切成子块；parentId 透传（MARKDOWN section 内复用此逻辑）。 */
    static List<DocumentChunk> slice(String documentId, String filename, String text, String parentId, ChunkConfig config) {
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isEmpty()) {
            return List.of();
        }
        return sliceFrom(documentId, filename, normalizedText, parentId, config, 0, Instant.now());
    }

    /** 从给定 chunkIndex 起切分 normalizedText（MARKDOWN 跨 section 需要连续递增的 index）。 */
    static List<DocumentChunk> sliceFrom(
            String documentId,
            String filename,
            String normalizedText,
            String parentId,
            ChunkConfig config,
            int startIndex,
            Instant createdAt
    ) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = startIndex;
        while (start < normalizedText.length()) {
            int end = Math.min(start + config.chunkSize(), normalizedText.length());
            String chunkContent = normalizedText.substring(start, end);
            chunks.add(Chunks.build(documentId, filename, chunkContent, chunkIndex, parentId, config, createdAt));
            if (end == normalizedText.length()) {
                break;
            }
            start = end - config.overlap();
            chunkIndex++;
        }
        return chunks;
    }
}
