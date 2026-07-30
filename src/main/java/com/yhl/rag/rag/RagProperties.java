package com.yhl.rag.rag;

import com.yhl.rag.chunk.ChunkStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private int chunkSize = 600;

    private boolean debugEnabled = false;

    private int chunkOverlap = 100;

    private Search search = new Search();

    private Context context = new Context();

    private QueryRewrite queryRewrite = new QueryRewrite();

    private Chunk chunk = new Chunk();

    private Contextual contextual = new Contextual();

    private Multimodal multimodal = new Multimodal();

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public Search getSearch() {
        return search;
    }

    public void setSearch(Search search) {
        this.search = search;
    }

    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context;
    }

    public QueryRewrite getQueryRewrite() {
        return queryRewrite;
    }

    public void setQueryRewrite(QueryRewrite queryRewrite) {
        this.queryRewrite = queryRewrite;
    }

    public Chunk getChunk() {
        return chunk;
    }

    public void setChunk(Chunk chunk) {
        this.chunk = chunk;
    }

    public Contextual getContextual() {
        return contextual;
    }

    public void setContextual(Contextual contextual) {
        this.contextual = contextual;
    }

    public Multimodal getMultimodal() {
        return multimodal;
    }

    public void setMultimodal(Multimodal multimodal) {
        this.multimodal = multimodal;
    }

    public static class Chunk {

        /** 分块策略：FIXED（默认，零回归）/ MARKDOWN（结构感知+独立父块）/ SEMANTIC（语义断块）。 */
        private ChunkStrategy strategy = ChunkStrategy.FIXED;

        private ParentDocument parentDocument = new ParentDocument();

        private Semantic semantic = new Semantic();

        public ChunkStrategy getStrategy() {
            return strategy;
        }

        public void setStrategy(ChunkStrategy strategy) {
            this.strategy = strategy;
        }

        public ParentDocument getParentDocument() {
            return parentDocument;
        }

        public void setParentDocument(ParentDocument parentDocument) {
            this.parentDocument = parentDocument;
        }

        public Semantic getSemantic() {
            return semantic;
        }

        public void setSemantic(Semantic semantic) {
            this.semantic = semantic;
        }
    }

    public static class ParentDocument {

        /** 开启后检索命中子块时按 parentId 回填父块正文给 LLM；默认关，零回归。 */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Semantic {

        /** 相邻句 cosine 跌破此阈值处断块，仅 SEMANTIC 策略使用。 */
        private double threshold = 0.6;

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }
    }

    public static class Contextual {

        /**
         * Contextual Retrieval（Anthropic 2024）：开启后入库时用 LLM 为每个子块生成一句
         * 「在父块/全文中的定位」前缀拼到待 embedding 文本前，提升召回；展示/回填仍用原文。
         * 默认关，零回归。父块/全文作为可缓存前缀复用 Prompt Caching 压成本。
         */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Multimodal {

        /**
         * 多模态 RAG：开启后接受 pdf/png/jpg 上传，PDF 抽文本+内嵌图，图片走 VL 图像 embedding
         * 进同一向量空间，文本 query 可召回图像 chunk。默认关：上传仍仅限 txt/md，纯文本路径零回归。
         */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class QueryRewrite {

        private boolean enabled = false;

        private Conversation conversation = new Conversation();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Conversation getConversation() {
            return conversation;
        }

        public void setConversation(Conversation conversation) {
            this.conversation = conversation;
        }
    }

    public static class Conversation {

        /**
         * 多轮会话 RAG：开启后结合对话历史做指代消解式 query 改写（"它的价格"→"X 的价格"）。
         * 默认关，无 conversationId / 空历史时回退单轮改写，零回归。需 query-rewrite.enabled 作总开关。
         */
        private boolean enabled = false;

        /** 改写时纳入上下文的最近会话轮数（保守默认 5）。 */
        private int historyTurns = 5;

        /** 累计轮数超过此阈值时，用 LLM 把早期轮次压缩成摘要（保守默认 10）。 */
        private int summaryThreshold = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getHistoryTurns() {
            return historyTurns;
        }

        public void setHistoryTurns(int historyTurns) {
            this.historyTurns = historyTurns;
        }

        public int getSummaryThreshold() {
            return summaryThreshold;
        }

        public void setSummaryThreshold(int summaryThreshold) {
            this.summaryThreshold = summaryThreshold;
        }
    }

    public static class Search {

        private int topK = 3;

        private double scoreThreshold = 0.3;

        /** 检索模式：vector（纯向量，默认，保持原行为）、hybrid（向量+BM25 RRF 融合）、hybrid_rerank（融合后再 bge-reranker 精排）。 */
        private RetrievalMode mode = RetrievalMode.VECTOR;

        /** 召回阶段每路（向量 / BM25）各取的候选数，融合后再裁剪到 topK。 */
        private int recallTopK = 50;

        /** RRF 融合常数 k，越大越弱化排名差异，经验值 60。 */
        private int rrfK = 60;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }

        public RetrievalMode getMode() {
            return mode;
        }

        public void setMode(RetrievalMode mode) {
            this.mode = mode;
        }

        public int getRecallTopK() {
            return recallTopK;
        }

        public void setRecallTopK(int recallTopK) {
            this.recallTopK = recallTopK;
        }

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }
    }

    public enum RetrievalMode {
        VECTOR,
        HYBRID,
        HYBRID_RERANK
    }

    public static class Context {

        private int maxChars = 3000;

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }
    }
}
