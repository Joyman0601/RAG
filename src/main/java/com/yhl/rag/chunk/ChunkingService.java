package com.yhl.rag.chunk;

import java.util.EnumMap;
import java.util.Map;

import com.yhl.rag.llm.EmbeddingClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 按 strategy 取对应 TextSplitter 执行分块。新增策略只需注册到此处。 */
@Service
public class ChunkingService {

    private final Map<ChunkStrategy, TextSplitter> splitters = new EnumMap<>(ChunkStrategy.class);

    @Autowired
    public ChunkingService(FixedWindowSplitter fixedWindowSplitter,
                           MarkdownSplitter markdownSplitter,
                           SemanticSplitter semanticSplitter) {
        register(fixedWindowSplitter);
        register(markdownSplitter);
        register(semanticSplitter);
    }

    /** 无依赖重载：方便测试与 DocumentService 旧构造器自带 splitter（语义需真实 EmbeddingClient）。 */
    public ChunkingService(EmbeddingClient embeddingClient) {
        this(new FixedWindowSplitter(), new MarkdownSplitter(), new SemanticSplitter(embeddingClient));
    }

    private void register(TextSplitter splitter) {
        splitters.put(splitter.strategy(), splitter);
    }

    public ChunkResult split(String documentId, String filename, String text, ChunkConfig config) {
        TextSplitter splitter = splitters.get(config.strategy());
        if (splitter == null) {
            splitter = splitters.get(ChunkStrategy.FIXED);
        }
        return splitter.split(documentId, filename, text, config);
    }
}
