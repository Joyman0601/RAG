package com.yhl.rag.tool;

import java.util.List;

public class SearchKnowledgeToolResult {

    private boolean answerable;

    private List<Context> contexts;

    private List<Source> sources;

    private int retrievedCount;

    public SearchKnowledgeToolResult() {
    }

    public SearchKnowledgeToolResult(boolean answerable, List<Context> contexts, List<Source> sources, int retrievedCount) {
        this.answerable = answerable;
        this.contexts = contexts;
        this.sources = sources;
        this.retrievedCount = retrievedCount;
    }

    public boolean isAnswerable() {
        return answerable;
    }

    public void setAnswerable(boolean answerable) {
        this.answerable = answerable;
    }

    public List<Context> getContexts() {
        return contexts;
    }

    public void setContexts(List<Context> contexts) {
        this.contexts = contexts;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    public int getRetrievedCount() {
        return retrievedCount;
    }

    public void setRetrievedCount(int retrievedCount) {
        this.retrievedCount = retrievedCount;
    }

    public static class Context {

        private String content;

        private String sourceId;

        private String documentId;

        private String title;

        private double score;

        public Context() {
        }

        public Context(String content, String sourceId, String documentId, String title, double score) {
            this.content = content;
            this.sourceId = sourceId;
            this.documentId = documentId;
            this.title = title;
            this.score = score;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getSourceId() {
            return sourceId;
        }

        public void setSourceId(String sourceId) {
            this.sourceId = sourceId;
        }

        public String getDocumentId() {
            return documentId;
        }

        public void setDocumentId(String documentId) {
            this.documentId = documentId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }
    }

    public static class Source {

        private String documentId;

        private String title;

        private int chunkIndex;

        public Source() {
        }

        public Source(String documentId, String title, int chunkIndex) {
            this.documentId = documentId;
            this.title = title;
            this.chunkIndex = chunkIndex;
        }

        public String getDocumentId() {
            return documentId;
        }

        public void setDocumentId(String documentId) {
            this.documentId = documentId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public void setChunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }
    }
}
