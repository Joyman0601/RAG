package com.yhl.rag.chunk;

import java.time.Instant;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentException;
import com.yhl.rag.document.DocumentStatus;

/** 子块构造工厂：统一 id/hash 与权限元数据填充，让各 splitter 只关心切分逻辑。 */
final class Chunks {

    private Chunks() {
    }

    static void validateConfig(ChunkConfig config) {
        if (config.chunkSize() <= 0) {
            throw new DocumentException("DOCUMENT_INVALID_CHUNK_CONFIG", "chunkSize 必须大于 0");
        }
        if (config.overlap() < 0) {
            throw new DocumentException("DOCUMENT_INVALID_CHUNK_CONFIG", "overlap 不能小于 0");
        }
        if (config.overlap() >= config.chunkSize()) {
            throw new DocumentException("DOCUMENT_INVALID_CHUNK_CONFIG", "overlap 必须小于 chunkSize");
        }
    }

    static DocumentChunk build(
            String documentId,
            String filename,
            String content,
            int chunkIndex,
            String parentId,
            ChunkConfig config,
            Instant createdAt
    ) {
        String contentHash = ChunkIds.sha256Hex(content);
        DocumentChunk chunk = new DocumentChunk(
                ChunkIds.stableChunkId(documentId, config.version(), chunkIndex, contentHash),
                documentId,
                filename,
                content,
                contentHash,
                chunkIndex,
                createdAt,
                config.tenantId(),
                config.ownerId(),
                config.departmentId(),
                config.visibility(),
                config.allowedUserIds(),
                config.allowedRoleIds(),
                DocumentStatus.ACTIVE,
                config.version(),
                config.permissionLevel()
        );
        chunk.setParentId(parentId);
        return chunk;
    }
}
