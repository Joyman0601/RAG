package com.yhl.rag.chunk;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 内存父块存储，默认 backend=memory 时装配；与 InMemoryVectorStore 对齐。 */
@Component
@ConditionalOnProperty(name = "vectorstore.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryParentStore implements ParentStore {

    private final ConcurrentMap<String, ParentBlock> parents = new ConcurrentHashMap<>();

    @Override
    public void saveAll(List<ParentBlock> parentBlocks) {
        if (parentBlocks == null) {
            return;
        }
        for (ParentBlock parent : parentBlocks) {
            if (parent != null && StringUtils.hasText(parent.getParentId())) {
                parents.put(parent.getParentId(), parent);
            }
        }
    }

    @Override
    public Optional<ParentBlock> findById(String parentId) {
        if (!StringUtils.hasText(parentId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(parents.get(parentId));
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        parents.entrySet().removeIf(entry -> documentId.equals(entry.getValue().getDocumentId()));
    }

    @Override
    public void deleteByDocumentIdAndVersion(String documentId, int version) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        parents.entrySet().removeIf(entry ->
                documentId.equals(entry.getValue().getDocumentId()) && entry.getValue().getVersion() == version);
    }
}
