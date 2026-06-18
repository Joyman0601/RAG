package com.yhl.rag.cost;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UsageRecordService {

    private final UsageRecordRepository repository;

    @Autowired
    public UsageRecordService(UsageRecordRepository repository) {
        this.repository = repository;
    }

    /** 无参构造：默认走内存实现，供测试与无 DB 场景直接 new 使用。 */
    public UsageRecordService() {
        this(new InMemoryUsageRecordRepository());
    }

    public void record(UsageRecord usageRecord) {
        repository.save(usageRecord);
    }

    public List<UsageRecord> list() {
        return repository.findAll();
    }

    public void clear() {
        repository.deleteAll();
    }

    public List<UsageSummaryRow> summarize(String tenantId, Instant from, Instant to) {
        return repository.summarize(tenantId, from, to);
    }
}
