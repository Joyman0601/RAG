package com.yhl.rag.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yhl.rag.document.DocumentChunk;
import org.springframework.util.StringUtils;

/**
 * BM25 关键词检索的共享实现：在一组已通过权限/版本过滤的 chunk 上做字面打分召回。
 * 内存与 pgvector 两种 VectorStore 共用此逻辑，保证混合检索行为一致——
 * pgvector 模式下稠密召回走 HNSW，关键词召回仍在应用层用本类计算。
 */
public final class Bm25Scorer {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private Bm25Scorer() {
    }

    public static List<VectorSearchResult> score(String queryText, List<DocumentChunk> corpus, int topK) {
        if (!StringUtils.hasText(queryText) || corpus == null || corpus.isEmpty()) {
            return List.of();
        }

        List<List<String>> docTokens = corpus.stream()
                .map(chunk -> tokenize(chunk.getContent()))
                .toList();
        List<String> queryTokens = tokenize(queryText).stream().distinct().toList();
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        int docCount = corpus.size();
        double avgDocLength = docTokens.stream().mapToInt(List::size).average().orElse(0.0);

        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> tokens : docTokens) {
            for (String term : Set.copyOf(tokens)) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        int limit = Math.max(topK, 1);
        return java.util.stream.IntStream.range(0, corpus.size())
                .mapToObj(i -> {
                    double score = bm25Score(queryTokens, docTokens.get(i), documentFrequency, docCount, avgDocLength);
                    return new VectorSearchResult(corpus.get(i), score, score > 0, "bm25");
                })
                .filter(result -> result.getScore() > 0)
                .sorted(Comparator.comparingDouble(VectorSearchResult::getScore).reversed())
                .limit(limit)
                .toList();
    }

    private static double bm25Score(
            List<String> queryTokens,
            List<String> docTokens,
            Map<String, Integer> documentFrequency,
            int docCount,
            double avgDocLength
    ) {
        Map<String, Integer> termFrequency = new HashMap<>();
        for (String term : docTokens) {
            termFrequency.merge(term, 1, Integer::sum);
        }
        int docLength = docTokens.size();
        double score = 0.0;
        for (String term : queryTokens) {
            int tf = termFrequency.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
            double denominator = tf + K1 * (1 - B + B * (avgDocLength == 0 ? 0 : docLength / avgDocLength));
            score += idf * (tf * (K1 + 1)) / denominator;
        }
        return score;
    }

    /** 轻量分词：CJK 字符切相邻二元组（bigram），ASCII 连续字母/数字作为词元；学习项目够用，无需第三方分词库。 */
    static List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String lower = text.toLowerCase();
        List<String> tokens = new ArrayList<>();
        StringBuilder asciiToken = new StringBuilder();
        List<Character> cjkRun = new ArrayList<>();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (!cjkRun.isEmpty()) {
                    addCjkBigrams(cjkRun, tokens);
                    cjkRun.clear();
                }
                asciiToken.append(c);
            } else if (isCjk(c)) {
                if (asciiToken.length() > 0) {
                    tokens.add(asciiToken.toString());
                    asciiToken.setLength(0);
                }
                cjkRun.add(c);
            } else {
                if (asciiToken.length() > 0) {
                    tokens.add(asciiToken.toString());
                    asciiToken.setLength(0);
                }
                if (!cjkRun.isEmpty()) {
                    addCjkBigrams(cjkRun, tokens);
                    cjkRun.clear();
                }
            }
        }
        if (asciiToken.length() > 0) {
            tokens.add(asciiToken.toString());
        }
        if (!cjkRun.isEmpty()) {
            addCjkBigrams(cjkRun, tokens);
        }
        return tokens;
    }

    private static void addCjkBigrams(List<Character> cjkRun, List<String> tokens) {
        if (cjkRun.size() == 1) {
            tokens.add(String.valueOf(cjkRun.get(0)));
            return;
        }
        for (int i = 0; i < cjkRun.size() - 1; i++) {
            tokens.add("" + cjkRun.get(i) + cjkRun.get(i + 1));
        }
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
