package com.yhl.rag.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EmbeddingCache {

    private final ConcurrentMap<String, EmbeddingCacheEntry> cache = new ConcurrentHashMap<>();

    public Optional<EmbeddingCacheEntry> get(String embeddingModel, String text) {
        return Optional.ofNullable(cache.get(key(embeddingModel, text)));
    }

    public void put(String embeddingModel, String text, List<Double> vector) {
        cache.put(key(embeddingModel, text), new EmbeddingCacheEntry(vector, estimateTokenCount(text), Instant.now()));
    }

    public String key(String embeddingModel, String text) {
        return normalizeModel(embeddingModel) + ":" + textHash(text);
    }

    public String textHash(String text) {
        return sha256Hex(text == null ? "" : text);
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    private static String normalizeModel(String embeddingModel) {
        return StringUtils.hasText(embeddingModel) ? embeddingModel : "default";
    }

    private static int estimateTokenCount(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
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
