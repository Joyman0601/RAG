package com.yhl.rag.vector;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentStatus;
import com.yhl.rag.document.DocumentVisibility;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * pgvector 后端：稠密召回走 HNSW（cosine 距离 <=>），权限/版本过滤翻译成 SQL WHERE，
 * BM25 关键词召回复用应用层 {@link Bm25Scorer}（先 SQL 过滤再内存打分）。
 * 仅在 vectorstore.backend=pgvector 时装配；默认走 {@link InMemoryVectorStore}。
 */
@Component
@ConditionalOnProperty(name = "vectorstore.backend", havingValue = "pgvector")
public class PgVectorStore implements VectorStore {

    /** 关键词召回的候选上限：先按过滤条件捞这么多 chunk 进内存跑 BM25，防止大语料全表加载。 */
    private static final int KEYWORD_CANDIDATE_LIMIT = 2000;

    private final JdbcTemplate jdbcTemplate;

    public PgVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(DocumentChunk chunk, List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_EMBEDDING,
                    "chunk 向量为空：" + (chunk == null ? null : chunk.getChunkId()));
        }
        if (chunk == null || !StringUtils.hasText(chunk.getChunkId())) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "chunkId 不能为空");
        }

        String sql = "INSERT INTO document_chunk ("
                + "chunk_id, document_id, tenant_id, filename, content, content_hash, chunk_index, "
                + "owner_id, department_id, visibility, allowed_user_ids, allowed_role_ids, "
                + "status, document_status, version, permission_level, created_at, embedding) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?::text[], ?, ?, ?, ?, ?, ?::vector) "
                + "ON CONFLICT (chunk_id) DO UPDATE SET "
                + "document_id = EXCLUDED.document_id, tenant_id = EXCLUDED.tenant_id, "
                + "filename = EXCLUDED.filename, content = EXCLUDED.content, "
                + "content_hash = EXCLUDED.content_hash, chunk_index = EXCLUDED.chunk_index, "
                + "owner_id = EXCLUDED.owner_id, department_id = EXCLUDED.department_id, "
                + "visibility = EXCLUDED.visibility, allowed_user_ids = EXCLUDED.allowed_user_ids, "
                + "allowed_role_ids = EXCLUDED.allowed_role_ids, status = EXCLUDED.status, "
                + "document_status = EXCLUDED.document_status, version = EXCLUDED.version, "
                + "permission_level = EXCLUDED.permission_level, created_at = EXCLUDED.created_at, "
                + "embedding = EXCLUDED.embedding";

        jdbcTemplate.update(sql,
                chunk.getChunkId(),
                chunk.getDocumentId(),
                chunk.getTenantId(),
                chunk.getFilename(),
                chunk.getContent(),
                chunk.getContentHash(),
                chunk.getChunkIndex(),
                chunk.getOwnerId(),
                chunk.getDepartmentId(),
                chunk.getVisibility() == null ? null : chunk.getVisibility().name(),
                toPgArrayLiteral(chunk.getAllowedUserIds()),
                toPgArrayLiteral(chunk.getAllowedRoleIds()),
                chunk.getStatus() == null ? DocumentStatus.ACTIVE.name() : chunk.getStatus().name(),
                chunk.getDocumentStatus() == null ? DocumentStatus.READY.name() : chunk.getDocumentStatus().name(),
                chunk.getVersion(),
                chunk.getPermissionLevel(),
                chunk.getCreatedAt() == null ? null : Timestamp.from(chunk.getCreatedAt()),
                toVectorLiteral(embedding));
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        validateSearchRequest(request);
        String vectorLiteral = toVectorLiteral(request.getQueryVector());

        List<Object> params = new ArrayList<>();
        params.add(vectorLiteral);
        String where = buildWhereClause(request, params);
        params.add(vectorLiteral);

        // cosine 距离 <=> ∈ [0,2]；score = 1 - distance ∈ [-1,1]，与内存实现的 cosineSimilarity 对齐。
        String sql = "SELECT *, 1 - (embedding <=> ?::vector) AS score FROM document_chunk "
                + where
                + " ORDER BY embedding <=> ?::vector LIMIT " + request.getTopK();

        List<VectorSearchResult> candidates = jdbcTemplate.query(sql, new ScoredRowMapper(request.getScoreThreshold()), params.toArray());
        if (request.isIncludeBelowThreshold()) {
            return candidates;
        }
        return candidates.stream().filter(VectorSearchResult::isIncluded).toList();
    }

    @Override
    public List<VectorSearchResult> keywordSearch(String queryText, VectorSearchRequest request) {
        if (request == null || !StringUtils.hasText(queryText)) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        String where = buildWhereClause(request, params);
        String sql = "SELECT * FROM document_chunk " + where + " LIMIT " + KEYWORD_CANDIDATE_LIMIT;
        List<DocumentChunk> corpus = jdbcTemplate.query(sql, new ChunkRowMapper(), params.toArray());
        return Bm25Scorer.score(queryText, corpus, request.getTopK());
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);
    }

    @Override
    public void deleteByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        List<String> valid = chunkIds.stream().filter(StringUtils::hasText).toList();
        if (valid.isEmpty()) {
            return;
        }
        String placeholders = String.join(", ", valid.stream().map(id -> "?").toList());
        jdbcTemplate.update("DELETE FROM document_chunk WHERE chunk_id IN (" + placeholders + ")", valid.toArray());
    }

    @Override
    public List<Double> getEmbedding(String chunkId) {
        if (!StringUtils.hasText(chunkId)) {
            return null;
        }
        List<String> rows = jdbcTemplate.query(
                "SELECT embedding::text FROM document_chunk WHERE chunk_id = ?",
                (rs, n) -> rs.getString(1),
                chunkId);
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return parseVectorLiteral(rows.get(0));
    }

    @Override
    public Map<String, List<Double>> getEmbeddingSnapshot() {
        Map<String, List<Double>> snapshot = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT chunk_id, embedding::text FROM document_chunk", rs -> {
            snapshot.put(rs.getString("chunk_id"), parseVectorLiteral(rs.getString(2)));
        });
        return Map.copyOf(snapshot);
    }

    /** 把 matchesFilter/canAccess 的内存判断翻译成参数化 SQL WHERE；这是「SQL 天然做权限过滤」的落点。 */
    private String buildWhereClause(VectorSearchRequest request, List<Object> params) {
        List<String> conditions = new ArrayList<>();

        conditions.add("tenant_id = ?");
        params.add(request.getTenantId());

        if (request.getStatus() != null) {
            conditions.add("status = ?");
            params.add(request.getStatus().name());
        }
        if (request.getDocumentStatus() != null) {
            conditions.add("document_status = ?");
            params.add(request.getDocumentStatus().name());
        }
        if (request.getVersion() != null) {
            conditions.add("version = ?");
            params.add(request.getVersion());
        }
        if (request.getDocumentVersions() != null) {
            // 仅保留 documentVersions 中登记、且 version 与登记值一致的 chunk。
            Map<String, Integer> versions = request.getDocumentVersions();
            if (versions.isEmpty()) {
                conditions.add("FALSE");
            } else {
                List<String> pairs = new ArrayList<>();
                for (Map.Entry<String, Integer> entry : versions.entrySet()) {
                    pairs.add("(document_id = ? AND version = ?)");
                    params.add(entry.getKey());
                    params.add(entry.getValue());
                }
                conditions.add("(" + String.join(" OR ", pairs) + ")");
            }
        }
        if (request.getVisibility() != null) {
            conditions.add("visibility = ?");
            params.add(request.getVisibility().name());
        }

        conditions.add(buildAccessClause(request, params));
        return "WHERE " + String.join(" AND ", conditions);
    }

    /** 对齐 InMemoryVectorStore.canAccess：按 chunk 的 visibility 决定可见性。 */
    private String buildAccessClause(VectorSearchRequest request, List<Object> params) {
        List<String> branches = new ArrayList<>();

        branches.add("visibility IN ('TENANT', 'PUBLIC')");

        if (StringUtils.hasText(request.getUserId())) {
            branches.add("(visibility = 'PRIVATE' AND owner_id = ?)");
            params.add(request.getUserId());
        }

        if (request.getDepartmentIds() != null && !request.getDepartmentIds().isEmpty()) {
            String placeholders = String.join(", ", request.getDepartmentIds().stream().map(d -> "?").toList());
            branches.add("(visibility = 'DEPARTMENT' AND department_id IN (" + placeholders + "))");
            params.addAll(request.getDepartmentIds());
        }

        // CUSTOM：allowed_user_ids 含当前用户，或 allowed_role_ids 与当前角色有交集。
        List<String> customParts = new ArrayList<>();
        if (StringUtils.hasText(request.getUserId())) {
            customParts.add("allowed_user_ids @> ARRAY[?]::text[]");
            params.add(request.getUserId());
        }
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            customParts.add("allowed_role_ids && ?::text[]");
            params.add(toPgArrayLiteral(request.getRoleIds()));
        }
        if (!customParts.isEmpty()) {
            branches.add("(visibility = 'CUSTOM' AND (" + String.join(" OR ", customParts) + "))");
        }

        return "(" + String.join(" OR ", branches) + ")";
    }

    private void validateSearchRequest(VectorSearchRequest request) {
        if (request == null) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "vector search request 不能为空");
        }
        if (request.getQueryVector() == null || request.getQueryVector().isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_EMBEDDING, "问题向量为空");
        }
        if (request.getTopK() <= 0) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "topK 必须大于 0");
        }
        if (request.getScoreThreshold() < -1 || request.getScoreThreshold() > 1) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "scoreThreshold 必须在 -1 到 1 之间");
        }
        if (!StringUtils.hasText(request.getTenantId())) {
            throw new LlmException(LlmErrorType.INVALID_STRUCTURED_OUTPUT, "tenantId 不能为空");
        }
    }

    private static String toVectorLiteral(List<Double> embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding.get(i));
        }
        return sb.append(']').toString();
    }

    private static List<Double> parseVectorLiteral(String literal) {
        if (!StringUtils.hasText(literal)) {
            return List.of();
        }
        String trimmed = literal.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return List.of();
        }
        String[] parts = trimmed.split(",");
        List<Double> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            values.add(Double.parseDouble(part.trim()));
        }
        return values;
    }

    private static String toPgArrayLiteral(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private static Set<String> readTextArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return Set.of();
        }
        Object raw = array.getArray();
        if (!(raw instanceof Object[] elements)) {
            return Set.of();
        }
        Set<String> result = new java.util.HashSet<>();
        for (Object element : elements) {
            if (element != null) {
                result.add(element.toString());
            }
        }
        return result;
    }

    private static DocumentChunk mapChunk(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        DocumentChunk chunk = new DocumentChunk(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getString("filename"),
                rs.getString("content"),
                rs.getString("content_hash"),
                rs.getInt("chunk_index"),
                createdAt == null ? null : createdAt.toInstant(),
                rs.getString("tenant_id"),
                rs.getString("owner_id"),
                rs.getString("department_id"),
                parseVisibility(rs.getString("visibility")),
                readTextArray(rs, "allowed_user_ids"),
                readTextArray(rs, "allowed_role_ids"),
                parseStatus(rs.getString("status"), DocumentStatus.ACTIVE),
                rs.getInt("version"),
                rs.getInt("permission_level"));
        chunk.setDocumentStatus(parseStatus(rs.getString("document_status"), DocumentStatus.READY));
        return chunk;
    }

    private static DocumentVisibility parseVisibility(String value) {
        return StringUtils.hasText(value) ? DocumentVisibility.valueOf(value) : DocumentVisibility.DEPARTMENT;
    }

    private static DocumentStatus parseStatus(String value, DocumentStatus fallback) {
        return StringUtils.hasText(value) ? DocumentStatus.valueOf(value) : fallback;
    }

    private static final class ChunkRowMapper implements RowMapper<DocumentChunk> {
        @Override
        public DocumentChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
            return mapChunk(rs);
        }
    }

    private static final class ScoredRowMapper implements RowMapper<VectorSearchResult> {
        private final double scoreThreshold;

        private ScoredRowMapper(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }

        @Override
        public VectorSearchResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            DocumentChunk chunk = mapChunk(rs);
            double score = rs.getDouble("score");
            boolean included = score >= scoreThreshold;
            return new VectorSearchResult(chunk, score, included,
                    included ? "score >= threshold" : "score < threshold");
        }
    }
}
