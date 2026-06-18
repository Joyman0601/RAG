package com.yhl.rag.cost;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * JDBC 实现：把 usage 落到 PostgreSQL 的 usage_record 表，支持审计与出账单查询。
 * 仅在 vectorstore.backend=pgvector 时装配；默认走 {@link InMemoryUsageRecordRepository}。
 */
@Repository
@ConditionalOnProperty(name = "vectorstore.backend", havingValue = "pgvector")
public class JdbcUsageRecordRepository implements UsageRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcUsageRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(UsageRecord r) {
        jdbcTemplate.update(
                "INSERT INTO usage_record (request_id, tenant_id, user_id, interface_name, model, "
                        + "prompt_tokens, completion_tokens, total_tokens, cached_tokens, estimated_cost, "
                        + "latency_ms, success, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.getRequestId(), r.getTenantId(), r.getUserId(), r.getInterfaceName(), r.getModel(),
                r.getPromptTokens(), r.getCompletionTokens(), r.getTotalTokens(), r.getCachedTokens(),
                r.getEstimatedCost(), r.getLatencyMs(), r.isSuccess(),
                r.getCreatedAt() == null ? null : Timestamp.from(r.getCreatedAt()));
    }

    @Override
    public List<UsageRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM usage_record ORDER BY id", new UsageRecordRowMapper());
    }

    @Override
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM usage_record");
    }

    @Override
    public List<UsageSummaryRow> summarize(String tenantId, Instant from, Instant to) {
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (StringUtils.hasText(tenantId)) {
            conditions.add("tenant_id = ?");
            params.add(tenantId);
        }
        if (from != null) {
            conditions.add("created_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            conditions.add("created_at <= ?");
            params.add(Timestamp.from(to));
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        String sql = "SELECT model, interface_name, COUNT(*) AS calls, "
                + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
                + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
                + "COALESCE(SUM(total_tokens), 0) AS total_tokens, "
                + "COALESCE(SUM(cached_tokens), 0) AS cached_tokens, "
                + "COALESCE(SUM(estimated_cost), 0) AS estimated_cost "
                + "FROM usage_record" + where
                + " GROUP BY model, interface_name ORDER BY model, interface_name";

        return jdbcTemplate.query(sql, (rs, n) -> new UsageSummaryRow(
                rs.getString("model"),
                rs.getString("interface_name"),
                rs.getLong("calls"),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getLong("total_tokens"),
                rs.getLong("cached_tokens"),
                rs.getBigDecimal("estimated_cost").doubleValue()), params.toArray());
    }

    private static final class UsageRecordRowMapper implements RowMapper<UsageRecord> {
        @Override
        public UsageRecord mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            Timestamp createdAt = rs.getTimestamp("created_at");
            BigDecimal cost = rs.getBigDecimal("estimated_cost");
            return new UsageRecord(
                    rs.getString("request_id"),
                    rs.getString("tenant_id"),
                    rs.getString("user_id"),
                    rs.getString("interface_name"),
                    rs.getString("model"),
                    rs.getInt("prompt_tokens"),
                    rs.getInt("completion_tokens"),
                    rs.getInt("total_tokens"),
                    rs.getInt("cached_tokens"),
                    cost == null ? 0.0 : cost.doubleValue(),
                    rs.getLong("latency_ms"),
                    rs.getBoolean("success"),
                    createdAt == null ? null : createdAt.toInstant());
        }
    }
}
