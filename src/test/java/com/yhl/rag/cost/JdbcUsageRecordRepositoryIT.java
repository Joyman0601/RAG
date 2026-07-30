package com.yhl.rag.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 真 PostgreSQL 集成测试。默认禁用；仅当设置 PG_URL 时运行，例如
 * `docker compose -f docker-compose.pgvector.yml up -d` 后
 * `PG_URL=jdbc:postgresql://localhost:5432/rag PG_USER=rag PG_PASSWORD=rag mvn test -Dtest=JdbcUsageRecordRepositoryIT`。
 * 验证 usage 写入与按 tenant/时间窗的聚合查询在 SQL 侧与内存语义一致。
 */
@EnabledIfEnvironmentVariable(named = "PG_URL", matches = ".+")
class JdbcUsageRecordRepositoryIT {

    private JdbcUsageRecordRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("PG_URL"));
        dataSource.setUsername(System.getenv().getOrDefault("PG_USER", "rag"));
        dataSource.setPassword(System.getenv().getOrDefault("PG_PASSWORD", "rag"));
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("DROP TABLE IF EXISTS usage_record");
        jdbcTemplate.execute("""
                CREATE TABLE usage_record (
                    id BIGSERIAL PRIMARY KEY,
                    request_id TEXT,
                    tenant_id TEXT,
                    user_id TEXT,
                    interface_name TEXT,
                    model TEXT,
                    prompt_tokens INTEGER,
                    completion_tokens INTEGER,
                    total_tokens INTEGER,
                    cached_tokens INTEGER,
                    estimated_cost NUMERIC(18, 6),
                    latency_ms BIGINT,
                    success BOOLEAN,
                    created_at TIMESTAMPTZ DEFAULT now()
                )""");

        repository = new JdbcUsageRecordRepository(jdbcTemplate);
    }

    @Test
    void save_thenFindAll_roundTrips() {
        repository.save(record("tenant-a", "FAST:default", "INTENT", 2, 1, 3, 0, 0.03));

        List<UsageRecord> all = repository.findAll();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getModel()).isEqualTo("FAST:default");
        assertThat(all.get(0).getTotalTokens()).isEqualTo(3);
    }

    @Test
    void summarize_aggregatesByModelAndInterface() {
        repository.save(record("tenant-a", "FAST:default", "INTENT", 2, 1, 3, 0, 0.03));
        repository.save(record("tenant-a", "FAST:default", "INTENT", 4, 2, 6, 1, 0.06));
        repository.save(record("tenant-a", "STANDARD:default", "RAG", 10, 5, 15, 0, 0.15));

        List<UsageSummaryRow> rows = repository.summarize("tenant-a", null, null);

        assertThat(rows).hasSize(2);
        UsageSummaryRow intent = rows.stream().filter(r -> r.interfaceName().equals("INTENT")).findFirst().orElseThrow();
        assertThat(intent.calls()).isEqualTo(2);
        assertThat(intent.totalTokens()).isEqualTo(9);
        assertThat(intent.cachedTokens()).isEqualTo(1);
        assertThat(intent.estimatedCost()).isEqualTo(0.09);
    }

    @Test
    void summarize_filtersByTenant() {
        repository.save(record("tenant-a", "FAST:default", "INTENT", 1, 1, 2, 0, 0.02));
        repository.save(record("tenant-b", "FAST:default", "INTENT", 1, 1, 2, 0, 0.02));

        List<UsageSummaryRow> rows = repository.summarize("tenant-a", null, null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).calls()).isEqualTo(1);
    }

    private static UsageRecord record(
            String tenantId, String model, String interfaceName,
            int prompt, int completion, int total, int cached, double cost
    ) {
        return new UsageRecord("req", tenantId, "user_001", interfaceName, model,
                prompt, completion, total, cached, cost, 10, true, Instant.now());
    }
}
