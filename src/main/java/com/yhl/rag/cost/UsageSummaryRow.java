package com.yhl.rag.cost;

/**
 * 用量汇总的一行：按 (model, interfaceName) 聚合后的 token 与成本统计。
 */
public record UsageSummaryRow(
        String model,
        String interfaceName,
        long calls,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long cachedTokens,
        double estimatedCost
) {
}
