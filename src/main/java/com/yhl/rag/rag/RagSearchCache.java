package com.yhl.rag.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.vector.VectorSearchResult;
import org.springframework.stereotype.Component;

@Component
public class RagSearchCache {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(3);

    private final ConcurrentMap<String, RagSearchCacheEntry> cache = new ConcurrentHashMap<>();

    private final Duration ttl;

    public RagSearchCache() {
        this(DEFAULT_TTL);
    }

    public RagSearchCache(Duration ttl) {
        this.ttl = ttl;
    }

    public Optional<RagSearchCacheEntry> get(String key) {
        RagSearchCacheEntry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.getCreatedAt().plus(ttl))) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void put(String key, java.util.List<VectorSearchResult> vectorResults, long embeddingDurationMs, long searchDurationMs) {
        cache.put(key, new RagSearchCacheEntry(vectorResults, embeddingDurationMs, searchDurationMs, Instant.now()));
    }

    public String key(
            String tenantId,
            String permissionSignature,
            String query,
            int topK,
            double scoreThreshold,
            String embeddingModel,
            long knowledgeBaseVersion
    ) {
        return String.join(":",
                safe(tenantId),
                safe(permissionSignature),
                sha256Hex(query == null ? "" : query),
                String.valueOf(topK),
                String.valueOf(scoreThreshold),
                safe(embeddingModel),
                String.valueOf(knowledgeBaseVersion)
        );
    }

    public String permissionSignature(CurrentUser currentUser) {
        if (currentUser == null) {
            return sha256Hex("anonymous");
        }
        String raw = String.join("|",
                safe(currentUser.getTenantId()),
                safe(currentUser.getUserId()),
                sorted(currentUser.getDepartmentIds()),
                sorted(currentUser.getRoleIds())
        );
        return sha256Hex(raw);
    }

    public String queryHash(String query) {
        return sha256Hex(query == null ? "" : query);
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    private static String sorted(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
