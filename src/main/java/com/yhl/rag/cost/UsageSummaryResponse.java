package com.yhl.rag.cost;

import java.util.List;

/**
 * 用量汇总响应：按 (model, interface) 分组的明细行 + 全量合计。
 */
public record UsageSummaryResponse(
        String tenantId,
        String from,
        String to,
        long totalCalls,
        long totalTokens,
        long totalCachedTokens,
        double totalEstimatedCost,
        List<UsageSummaryRow> rows
) {
}
