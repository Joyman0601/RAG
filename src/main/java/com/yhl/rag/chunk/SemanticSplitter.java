package com.yhl.rag.chunk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.llm.EmbeddingClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 语义分块：按句切分 → 逐句 embedding → 相邻句 cosine 跌破 threshold 处断块。
 * 让语义连续的句子留在同一子块，话题切换处自然分界，比固定窗口更贴合检索粒度。
 * 决策：语义只切子块、不产父块（父块回填主要服务 MARKDOWN）。
 */
@Component
public class SemanticSplitter implements TextSplitter {

    private final EmbeddingClient embeddingClient;

    @Autowired
    public SemanticSplitter(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.SEMANTIC;
    }

    @Override
    public ChunkResult split(String documentId, String filename, String text, ChunkConfig config) {
        Chunks.validateConfig(config);
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return ChunkResult.childrenOnly(List.of());
        }

        Instant createdAt = Instant.now();
        List<DocumentChunk> children = new ArrayList<>();
        List<Double> previousVector = embeddingClient.embed(sentences.get(0));
        StringBuilder segment = new StringBuilder(sentences.get(0));
        int chunkIndex = 0;

        for (int i = 1; i < sentences.size(); i++) {
            List<Double> currentVector = embeddingClient.embed(sentences.get(i));
            double similarity = cosine(previousVector, currentVector);
            if (similarity < config.semanticThreshold()) {
                children.add(Chunks.build(documentId, filename, segment.toString(), chunkIndex++, null, config, createdAt));
                segment.setLength(0);
            }
            segment.append(sentences.get(i));
            previousVector = currentVector;
        }
        children.add(Chunks.build(documentId, filename, segment.toString(), chunkIndex, null, config, createdAt));

        return ChunkResult.childrenOnly(children);
    }

    /** 句子切分：以 。！？!?\n 为界，保留句末标点，丢弃空白句。 */
    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null) {
            return sentences;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                addIfNotBlank(sentences, current);
                continue;
            }
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') {
                addIfNotBlank(sentences, current);
            }
        }
        addIfNotBlank(sentences, current);
        return sentences;
    }

    private static void addIfNotBlank(List<String> sentences, StringBuilder buffer) {
        String sentence = buffer.toString().trim();
        if (!sentence.isEmpty()) {
            sentences.add(sentence);
        }
        buffer.setLength(0);
    }

    private static double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.size() != right.size() || left.isEmpty()) {
            return 0.0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
