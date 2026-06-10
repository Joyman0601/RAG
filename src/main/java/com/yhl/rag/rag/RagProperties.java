package com.yhl.rag.rag;

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

    public static class QueryRewrite {

        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
