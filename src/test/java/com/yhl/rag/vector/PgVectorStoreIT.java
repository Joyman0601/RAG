package com.yhl.rag.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentStatus;
import com.yhl.rag.document.DocumentVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 真 pgvector 集成测试。默认禁用；仅当设置 PG_URL（可选 PG_USER/PG_PASSWORD）时运行，
 * 例如先 `docker compose -f docker-compose.pgvector.yml up -d`，再
 * `PG_URL=jdbc:postgresql://localhost:5432/rag PG_USER=rag PG_PASSWORD=rag mvn test -Dtest=PgVectorStoreIT`。
 *
 * <p>用 2 维向量自建一张测试表（隔离于生产的 vector(4096) DDL），覆盖与
 * {@link InMemoryVectorStoreTest} 等价的权限/版本/关键词场景，验证内存语义在 SQL 侧一致复现。
 */
@EnabledIfEnvironmentVariable(named = "PG_URL", matches = ".+")
class PgVectorStoreIT {

    private PgVectorStore vectorStore;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("PG_URL"));
        dataSource.setUsername(System.getenv().getOrDefault("PG_USER", "rag"));
        dataSource.setPassword(System.getenv().getOrDefault("PG_PASSWORD", "rag"));
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("DROP TABLE IF EXISTS document_chunk");
        jdbcTemplate.execute("""
                CREATE TABLE document_chunk (
                    chunk_id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL,
                    tenant_id TEXT NOT NULL,
                    filename TEXT,
                    content TEXT,
                    content_hash TEXT,
                    chunk_index INTEGER NOT NULL DEFAULT 0,
                    owner_id TEXT,
                    department_id TEXT,
                    visibility TEXT NOT NULL DEFAULT 'DEPARTMENT',
                    allowed_user_ids TEXT[] NOT NULL DEFAULT '{}',
                    allowed_role_ids TEXT[] NOT NULL DEFAULT '{}',
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    document_status TEXT NOT NULL DEFAULT 'READY',
                    version INTEGER NOT NULL DEFAULT 1,
                    permission_level INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ,
                    embedding vector(2)
                )""");
        jdbcTemplate.execute(
                "CREATE INDEX ON document_chunk USING hnsw (embedding vector_cosine_ops)");

        vectorStore = new PgVectorStore(jdbcTemplate);
    }

    @Test
    void search_returnsTopKByCosineScore() {
        vectorStore.save(chunk("c1", "doc1", "refund policy", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.save(chunk("c2", "doc2", "travel policy", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(0.0, 1.0));

        List<VectorSearchResult> results = vectorStore.search(request(List.of(1.0, 0.0), 1, 0.1, "user_001", "dept-a"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunk().getChunkId()).isEqualTo("c1");
        assertThat(results.get(0).isIncluded()).isTrue();
    }

    @Test
    void search_filtersByPermissionInSql() {
        vectorStore.save(chunk("private-other", "doc1", "private content", DocumentVisibility.PRIVATE, "user_002", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.save(chunk("internal-other-dept", "doc2", "internal content", DocumentVisibility.DEPARTMENT, "user_002", "dept-b", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.save(chunk("public", "doc3", "public content", DocumentVisibility.PUBLIC, "user_002", "dept-b", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));

        List<VectorSearchResult> results = vectorStore.search(request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a"));

        assertThat(results).extracting(r -> r.getChunk().getChunkId()).containsExactly("public");
    }

    @Test
    void search_differentTenant_doesNotRecall() {
        DocumentChunk foreign = chunk("foreign", "doc1", "foreign tenant", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1);
        foreign.setTenantId("tenant-b");
        vectorStore.save(foreign, List.of(1.0, 0.0));

        List<VectorSearchResult> results = vectorStore.search(request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a"));

        assertThat(results).isEmpty();
    }

    @Test
    void search_privateDocument_ownerOnly() {
        vectorStore.save(chunk("own", "doc1", "own private", DocumentVisibility.PRIVATE, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.save(chunk("other", "doc2", "other private", DocumentVisibility.PRIVATE, "user_002", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));

        List<VectorSearchResult> results = vectorStore.search(request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a"));

        assertThat(results).extracting(r -> r.getChunk().getChunkId()).containsExactly("own");
    }

    @Test
    void search_customVisibility_userOrRoleAllowed() {
        DocumentChunk userAllowed = chunk("user-allowed", "doc1", "custom user", DocumentVisibility.CUSTOM, "user_002", "dept-b", DocumentStatus.ACTIVE, 1);
        userAllowed.setAllowedUserIds(Set.of("user_001"));
        DocumentChunk roleAllowed = chunk("role-allowed", "doc2", "custom role", DocumentVisibility.CUSTOM, "user_002", "dept-b", DocumentStatus.ACTIVE, 1);
        roleAllowed.setAllowedRoleIds(Set.of("finance"));
        DocumentChunk denied = chunk("denied", "doc3", "custom denied", DocumentVisibility.CUSTOM, "user_002", "dept-b", DocumentStatus.ACTIVE, 1);
        denied.setAllowedRoleIds(Set.of("hr"));
        vectorStore.save(userAllowed, List.of(1.0, 0.0));
        vectorStore.save(roleAllowed, List.of(0.9, 0.1));
        vectorStore.save(denied, List.of(1.0, 0.0));
        VectorSearchRequest request = request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a");
        request.setRoleIds(Set.of("finance"));

        List<VectorSearchResult> results = vectorStore.search(request);

        assertThat(results).extracting(r -> r.getChunk().getChunkId())
                .containsExactlyInAnyOrder("user-allowed", "role-allowed");
    }

    @Test
    void search_onlyRecallsCurrentVersion() {
        vectorStore.save(chunk("old", "doc1", "old content", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.save(chunk("current", "doc1", "current content", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 2), List.of(1.0, 0.0));
        VectorSearchRequest request = request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a");
        request.setDocumentVersions(Map.of("doc1", 2));

        List<VectorSearchResult> results = vectorStore.search(request);

        assertThat(results).singleElement()
                .extracting(r -> r.getChunk().getChunkId()).isEqualTo("current");
    }

    @Test
    void deleteByDocumentId_removesChunks() {
        vectorStore.save(chunk("c1", "doc1", "deleted document", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.deleteByDocumentId("doc1");

        List<VectorSearchResult> results = vectorStore.search(request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a"));

        assertThat(results).isEmpty();
    }

    @Test
    void keywordSearch_exactCodeMatchRanksFirst_respectingPermission() {
        vectorStore.save(chunk("right", "doc1", "订单 A12345 退款失败 原因余额不足", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));
        vectorStore.save(chunk("wrong", "doc2", "订单 A12346 退款成功 已到账", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(1.0, 0.0));

        List<VectorSearchResult> results = vectorStore.keywordSearch("A12345 退款失败", request(List.of(1.0, 0.0), 10, 0.1, "user_001", "dept-a"));

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getChunk().getChunkId()).isEqualTo("right");
        assertThat(results.get(0).getDebugInfo()).isEqualTo("bm25");
    }

    @Test
    void getEmbedding_roundTrips() {
        vectorStore.save(chunk("c1", "doc1", "content", DocumentVisibility.PUBLIC, "user_001", "dept-a", DocumentStatus.ACTIVE, 1), List.of(0.25, 0.75));

        List<Double> embedding = vectorStore.getEmbedding("c1");

        assertThat(embedding).containsExactly(0.25, 0.75);
    }

    private static VectorSearchRequest request(List<Double> queryVector, int topK, double scoreThreshold, String userId, String department) {
        VectorSearchRequest request = new VectorSearchRequest();
        request.setQueryVector(queryVector);
        request.setTopK(topK);
        request.setScoreThreshold(scoreThreshold);
        request.setTenantId("tenant-default");
        request.setUserId(userId);
        request.setDepartment(department);
        request.setDepartmentIds(Set.of(department));
        request.setStatus(DocumentStatus.ACTIVE);
        request.setDocumentStatus(DocumentStatus.READY);
        return request;
    }

    private static DocumentChunk chunk(
            String chunkId, String documentId, String content, DocumentVisibility visibility,
            String ownerId, String department, DocumentStatus status, int version
    ) {
        return new DocumentChunk(
                chunkId, documentId, documentId + ".md", content, "hash-" + chunkId, 0,
                Instant.now(), "tenant-default", ownerId, department, visibility,
                Set.of(), Set.of(), status, version, 1);
    }
}
