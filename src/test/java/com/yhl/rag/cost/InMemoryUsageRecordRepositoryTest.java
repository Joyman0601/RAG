package com.yhl.rag.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

class InMemoryUsageRecordRepositoryTest {

    @Test
    void summarize_aggregatesByModelAndInterface() {
        InMemoryUsageRecordRepository repo = new InMemoryUsageRecordRepository();
        repo.save(record("tenant-a", "FAST:default", "INTENT", 2, 1, 3, 0, 0.03, now()));
        repo.save(record("tenant-a", "FAST:default", "INTENT", 4, 2, 6, 1, 0.06, now()));
        repo.save(record("tenant-a", "STANDARD:default", "RAG", 10, 5, 15, 0, 0.15, now()));

        List<UsageSummaryRow> rows = repo.summarize("tenant-a", null, null);

        assertThat(rows).hasSize(2);
        UsageSummaryRow intent = rows.stream().filter(r -> r.interfaceName().equals("INTENT")).findFirst().orElseThrow();
        assertThat(intent.calls()).isEqualTo(2);
        assertThat(intent.totalTokens()).isEqualTo(9);
        assertThat(intent.cachedTokens()).isEqualTo(1);
        assertThat(intent.estimatedCost()).isEqualTo(0.09);
    }

    @Test
    void summarize_filtersByTenant() {
        InMemoryUsageRecordRepository repo = new InMemoryUsageRecordRepository();
        repo.save(record("tenant-a", "FAST:default", "INTENT", 1, 1, 2, 0, 0.02, now()));
        repo.save(record("tenant-b", "FAST:default", "INTENT", 1, 1, 2, 0, 0.02, now()));

        List<UsageSummaryRow> rows = repo.summarize("tenant-a", null, null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).calls()).isEqualTo(1);
    }

    @Test
    void summarize_filtersByTimeWindow() {
        InMemoryUsageRecordRepository repo = new InMemoryUsageRecordRepository();
        Instant old = now().minus(2, ChronoUnit.DAYS);
        Instant recent = now();
        repo.save(record("tenant-a", "FAST:default", "INTENT", 1, 1, 2, 0, 0.02, old));
        repo.save(record("tenant-a", "FAST:default", "INTENT", 1, 1, 2, 0, 0.02, recent));

        List<UsageSummaryRow> rows = repo.summarize("tenant-a", now().minus(1, ChronoUnit.DAYS), null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).calls()).isEqualTo(1);
    }

    private static Instant now() {
        return Instant.now();
    }

    private static UsageRecord record(
            String tenantId, String model, String interfaceName,
            int prompt, int completion, int total, int cached, double cost, Instant createdAt
    ) {
        return new UsageRecord("req", tenantId, "user_001", interfaceName, model,
                prompt, completion, total, cached, cost, 10, true, createdAt);
    }
}
