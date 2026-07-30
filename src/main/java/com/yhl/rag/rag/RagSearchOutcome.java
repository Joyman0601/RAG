package com.yhl.rag.rag;

import java.util.List;

public record RagSearchOutcome(
        List<RagSearchResult> results,
        long embeddingDurationMs,
        long searchDurationMs
) {
}
