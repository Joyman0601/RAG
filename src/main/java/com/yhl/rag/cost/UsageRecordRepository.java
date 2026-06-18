package com.yhl.rag.cost;

import java.time.Instant;
import java.util.List;

/**
 * usage 记录的存储抽象。内存实现做默认（保证无 DB 也能跑、单测不依赖数据库），
 * JDBC 实现仅在 pgvector 模式下装配，把用量落到 PostgreSQL 以支持审计与出账单。
 */
public interface UsageRecordRepository {

    void save(UsageRecord usageRecord);

    /** 返回当前可见的全部记录（内存实现=进程内记录；JDBC 实现=表内记录）。 */
    List<UsageRecord> findAll();

    void deleteAll();

    /** 按模型 + 接口聚合 token 与成本，可选按租户与时间窗过滤。供成本审计/出账单用。 */
    List<UsageSummaryRow> summarize(String tenantId, Instant from, Instant to);
}
