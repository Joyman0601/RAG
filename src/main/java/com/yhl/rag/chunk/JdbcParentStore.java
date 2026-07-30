package com.yhl.rag.chunk;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.yhl.rag.document.DocumentVisibility;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * pgvector 后端的父块存储：document_parent 表，权限列与 document_chunk 对齐。
 * 仅在 vectorstore.backend=pgvector 时装配；默认走 {@link InMemoryParentStore}。
 */
@Component
@ConditionalOnProperty(name = "vectorstore.backend", havingValue = "pgvector")
public class JdbcParentStore implements ParentStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcParentStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveAll(List<ParentBlock> parents) {
        if (parents == null || parents.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO document_parent ("
                + "parent_id, document_id, tenant_id, content, owner_id, department_id, "
                + "visibility, allowed_user_ids, allowed_role_ids, version, permission_level) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?::text[], ?, ?) "
                + "ON CONFLICT (parent_id) DO UPDATE SET "
                + "document_id = EXCLUDED.document_id, tenant_id = EXCLUDED.tenant_id, "
                + "content = EXCLUDED.content, owner_id = EXCLUDED.owner_id, "
                + "department_id = EXCLUDED.department_id, visibility = EXCLUDED.visibility, "
                + "allowed_user_ids = EXCLUDED.allowed_user_ids, allowed_role_ids = EXCLUDED.allowed_role_ids, "
                + "version = EXCLUDED.version, permission_level = EXCLUDED.permission_level";
        for (ParentBlock parent : parents) {
            if (parent == null || !StringUtils.hasText(parent.getParentId())) {
                continue;
            }
            jdbcTemplate.update(sql,
                    parent.getParentId(),
                    parent.getDocumentId(),
                    parent.getTenantId(),
                    parent.getContent(),
                    parent.getOwnerId(),
                    parent.getDepartmentId(),
                    parent.getVisibility() == null ? null : parent.getVisibility().name(),
                    toPgArrayLiteral(parent.getAllowedUserIds()),
                    toPgArrayLiteral(parent.getAllowedRoleIds()),
                    parent.getVersion(),
                    parent.getPermissionLevel());
        }
    }

    @Override
    public Optional<ParentBlock> findById(String parentId) {
        if (!StringUtils.hasText(parentId)) {
            return Optional.empty();
        }
        List<ParentBlock> rows = jdbcTemplate.query(
                "SELECT * FROM document_parent WHERE parent_id = ?",
                new ParentRowMapper(),
                parentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        jdbcTemplate.update("DELETE FROM document_parent WHERE document_id = ?", documentId);
    }

    @Override
    public void deleteByDocumentIdAndVersion(String documentId, int version) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        jdbcTemplate.update("DELETE FROM document_parent WHERE document_id = ? AND version = ?", documentId, version);
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

    private static final class ParentRowMapper implements RowMapper<ParentBlock> {
        @Override
        public ParentBlock mapRow(ResultSet rs, int rowNum) throws SQLException {
            String visibility = rs.getString("visibility");
            return new ParentBlock(
                    rs.getString("parent_id"),
                    rs.getString("document_id"),
                    rs.getString("content"),
                    rs.getInt("version"),
                    rs.getString("tenant_id"),
                    rs.getString("owner_id"),
                    rs.getString("department_id"),
                    StringUtils.hasText(visibility) ? DocumentVisibility.valueOf(visibility) : DocumentVisibility.DEPARTMENT,
                    readTextArray(rs, "allowed_user_ids"),
                    readTextArray(rs, "allowed_role_ids"),
                    rs.getInt("permission_level"));
        }
    }
}
