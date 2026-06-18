package com.yhl.rag.cost;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * 用量汇总只读接口：按租户与时间窗聚合 token 与成本，用于成本审计 / 出账单。
 * 数据来源是注入的 {@link UsageRecordService}——pgvector 模式下读 PG 表，内存模式下读进程内记录。
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageRecordService usageRecordService;

    public UsageController(UsageRecordService usageRecordService) {
        this.usageRecordService = usageRecordService;
    }

    @GetMapping("/summary")
    public UsageSummaryResponse summary(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");

        List<UsageSummaryRow> rows = usageRecordService.summarize(tenantId, fromInstant, toInstant);

        long totalCalls = rows.stream().mapToLong(UsageSummaryRow::calls).sum();
        long totalTokens = rows.stream().mapToLong(UsageSummaryRow::totalTokens).sum();
        long totalCachedTokens = rows.stream().mapToLong(UsageSummaryRow::cachedTokens).sum();
        double totalCost = rows.stream().mapToDouble(UsageSummaryRow::estimatedCost).sum();

        return new UsageSummaryResponse(
                tenantId, from, to, totalCalls, totalTokens, totalCachedTokens, totalCost, rows);
    }

    private static Instant parseInstant(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 必须是 ISO-8601 时间格式，例如 2026-06-18T00:00:00Z");
        }
    }
}
