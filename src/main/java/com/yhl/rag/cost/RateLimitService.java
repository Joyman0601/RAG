package com.yhl.rag.cost;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public void checkRateLimit(String userId, String interfaceName, int maxPerMinute) {
        String key = (userId == null ? "unknown" : userId) + ":" + interfaceName;
        long currentMinute = Instant.now().getEpochSecond() / 60;
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.minute != currentMinute) {
                return new WindowCounter(currentMinute);
            }
            return existing;
        });
        int current = counter.count.incrementAndGet();
        if (current > maxPerMinute) {
            throw new CostException(CostErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试");
        }
    }

    public void clear() {
        counters.clear();
    }

    private static class WindowCounter {
        private final long minute;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long minute) {
            this.minute = minute;
        }
    }
}
