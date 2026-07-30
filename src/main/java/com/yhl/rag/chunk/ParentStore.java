package com.yhl.rag.chunk;

import java.util.List;
import java.util.Optional;

/** 独立父块存储：检索命中子块后按 parentId 回填父块正文。内存与 pgvector 两套实现按 backend 装配。 */
public interface ParentStore {

    void saveAll(List<ParentBlock> parents);

    Optional<ParentBlock> findById(String parentId);

    void deleteByDocumentId(String documentId);

    void deleteByDocumentIdAndVersion(String documentId, int version);
}
