package com.yhl.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.yhl.rag.document.DocumentVisibility;
import org.junit.jupiter.api.Test;

class InMemoryParentStoreTest {

    @Test
    void saveAndFindById_roundTripsParentBlock() {
        InMemoryParentStore store = new InMemoryParentStore();
        store.saveAll(List.of(parent("p1", "doc-1", 1, "父块内容")));

        assertThat(store.findById("p1")).isPresent()
                .get().satisfies(parent -> assertThat(parent.getContent()).isEqualTo("父块内容"));
        assertThat(store.findById("missing")).isEmpty();
    }

    @Test
    void deleteByDocumentIdAndVersion_removesOnlyThatVersion() {
        InMemoryParentStore store = new InMemoryParentStore();
        store.saveAll(List.of(
                parent("p1", "doc-1", 1, "v1"),
                parent("p2", "doc-1", 2, "v2")
        ));

        store.deleteByDocumentIdAndVersion("doc-1", 1);

        assertThat(store.findById("p1")).isEmpty();
        assertThat(store.findById("p2")).isPresent();
    }

    @Test
    void deleteByDocumentId_removesAllVersions() {
        InMemoryParentStore store = new InMemoryParentStore();
        store.saveAll(List.of(
                parent("p1", "doc-1", 1, "v1"),
                parent("p2", "doc-1", 2, "v2"),
                parent("p3", "doc-2", 1, "other")
        ));

        store.deleteByDocumentId("doc-1");

        assertThat(store.findById("p1")).isEmpty();
        assertThat(store.findById("p2")).isEmpty();
        assertThat(store.findById("p3")).isPresent();
    }

    private static ParentBlock parent(String parentId, String documentId, int version, String content) {
        return new ParentBlock(
                parentId,
                documentId,
                content,
                version,
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
