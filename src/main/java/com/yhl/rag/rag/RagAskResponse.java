package com.yhl.rag.rag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public class RagAskResponse {

    private String answer;

    private List<RagSource> sources;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<RagRetrievedChunk> retrievedChunks;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private RagTokenUsage tokenUsage;

    // 实际生效的检索模式（前端三模式对比页展示用）。
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private RagProperties.RetrievalMode effectiveMode;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long embeddingDurationMs;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long searchDurationMs;

    public RagAskResponse() {
    }

    public RagAskResponse(String answer, List<RagSource> sources) {
        this.answer = answer;
        this.sources = sources;
    }

    public RagAskResponse(String answer, List<RagSource> sources, List<RagRetrievedChunk> retrievedChunks) {
        this.answer = answer;
        this.sources = sources;
        this.retrievedChunks = retrievedChunks;
    }

    public RagAskResponse(String answer, List<RagSource> sources, List<RagRetrievedChunk> retrievedChunks, RagTokenUsage tokenUsage) {
        this.answer = answer;
        this.sources = sources;
        this.retrievedChunks = retrievedChunks;
        this.tokenUsage = tokenUsage;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<RagSource> getSources() {
        return sources;
    }

    public void setSources(List<RagSource> sources) {
        this.sources = sources;
    }

    public List<RagRetrievedChunk> getRetrievedChunks() {
        return retrievedChunks;
    }

    public void setRetrievedChunks(List<RagRetrievedChunk> retrievedChunks) {
        this.retrievedChunks = retrievedChunks;
    }

    public RagTokenUsage getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(RagTokenUsage tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    public RagProperties.RetrievalMode getEffectiveMode() {
        return effectiveMode;
    }

    public void setEffectiveMode(RagProperties.RetrievalMode effectiveMode) {
        this.effectiveMode = effectiveMode;
    }

    public Long getEmbeddingDurationMs() {
        return embeddingDurationMs;
    }

    public void setEmbeddingDurationMs(Long embeddingDurationMs) {
        this.embeddingDurationMs = embeddingDurationMs;
    }

    public Long getSearchDurationMs() {
        return searchDurationMs;
    }

    public void setSearchDurationMs(Long searchDurationMs) {
        this.searchDurationMs = searchDurationMs;
    }
}
