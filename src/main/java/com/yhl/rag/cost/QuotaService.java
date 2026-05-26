package com.yhl.rag.cost;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

@Service
public class QuotaService {

    private final ConcurrentMap<String, AtomicInteger> userDailyUsage = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, AtomicInteger> tenantDailyUsage = new ConcurrentHashMap<>();

    public void checkQuota(String tenantId, String userId, int estimatedTokens, CostProperties properties) {
        int userUsed = userDailyUsage.getOrDefault(key(userId), new AtomicInteger()).get();
        int tenantUsed = tenantDailyUsage.getOrDefault(key(tenantId), new AtomicInteger()).get();
        if (userUsed + estimatedTokens > properties.getUserDailyTokenQuota()) {
            throw new CostException(CostErrorCode.QUOTA_EXCEEDED, "用户今日 token 配额已超限");
        }
        if (tenantUsed + estimatedTokens > properties.getTenantDailyTokenQuota()) {
            throw new CostException(CostErrorCode.QUOTA_EXCEEDED, "租户今日 token 配额已超限");
        }
    }

    public void addUsage(String tenantId, String userId, int tokens) {
        userDailyUsage.computeIfAbsent(key(userId), ignored -> new AtomicInteger()).addAndGet(tokens);
        tenantDailyUsage.computeIfAbsent(key(tenantId), ignored -> new AtomicInteger()).addAndGet(tokens);
    }

    public int userUsed(String userId) {
        return userDailyUsage.getOrDefault(key(userId), new AtomicInteger()).get();
    }

    public void clear() {
        userDailyUsage.clear();
        tenantDailyUsage.clear();
    }

    private static String key(String id) {
        return LocalDate.now() + ":" + (id == null ? "unknown" : id);
    }
}
