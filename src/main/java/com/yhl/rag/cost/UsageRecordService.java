package com.yhl.rag.cost;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

@Service
public class UsageRecordService {

    private final List<UsageRecord> records = new CopyOnWriteArrayList<>();

    public void record(UsageRecord usageRecord) {
        records.add(usageRecord);
    }

    public List<UsageRecord> list() {
        return List.copyOf(records);
    }

    public void clear() {
        records.clear();
    }
}
