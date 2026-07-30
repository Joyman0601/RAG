package com.yhl.rag.chunk;

import java.util.Set;

import com.yhl.rag.document.DocumentVisibility;

/**
 * 一次分块所需的全部参数：尺寸/策略 + chunk 落库要带的租户与权限元数据。
 * documentId / filename / text 由 {@link TextSplitter#split} 单独传入，不进 config。
 */
public record ChunkConfig(
        ChunkStrategy strategy,
        int chunkSize,
        int overlap,
        double semanticThreshold,
        int version,
        String tenantId,
        String ownerId,
        String departmentId,
        DocumentVisibility visibility,
        Set<String> allowedUserIds,
        Set<String> allowedRoleIds,
        int permissionLevel
) {
    public ChunkConfig {
        allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
        allowedRoleIds = allowedRoleIds == null ? Set.of() : Set.copyOf(allowedRoleIds);
    }
}
