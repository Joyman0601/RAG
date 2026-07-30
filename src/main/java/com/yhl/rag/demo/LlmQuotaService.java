package com.yhl.rag.demo;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 每日 LLM chat 调用计数（内存版）。
 * 只对面向用户的 chat 计数，不切 embedding/rerank —— 一次问答会带若干 embedding 调用，
 * 按底层 API 计数会让 500 次上限迅速耗尽，与"面试官问了几次"的直觉不符。
 * 重启即清零；面试演示场景够用，无需 Redis 持久化。
 */
@Service
public class LlmQuotaService {

    private static final Logger log = LoggerFactory.getLogger(LlmQuotaService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final DemoProperties demoProperties;
    private final AtomicLong count = new AtomicLong(0);
    private volatile LocalDate windowDate = LocalDate.now(ZONE);

    public LlmQuotaService(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    public synchronized void assertAndIncrement() {
        rolloverIfNewDay();
        long limit = demoProperties.maxDailyLlmCalls();
        long current = count.get();
        if (current >= limit) {
            log.warn("llm_quota_exhausted date={} count={} limit={}", windowDate, current, limit);
            throw new LlmQuotaExceededException(limit);
        }
        long next = count.incrementAndGet();
        if (next % 50 == 0 || next >= limit - 10) {
            log.info("llm_quota_progress date={} count={} limit={}", windowDate, next, limit);
        }
    }

    public synchronized QuotaSnapshot snapshot() {
        rolloverIfNewDay();
        return new QuotaSnapshot(windowDate.toString(), count.get(), demoProperties.maxDailyLlmCalls());
    }

    private void rolloverIfNewDay() {
        LocalDate today = LocalDate.now(ZONE);
        if (!today.equals(windowDate)) {
            log.info("llm_quota_rollover from={} to={} previousCount={}", windowDate, today, count.get());
            windowDate = today;
            count.set(0);
        }
    }

    public record QuotaSnapshot(String date, long used, long limit) {}
}
