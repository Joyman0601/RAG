package com.yhl.rag.rag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public class RagAskResponse {

    private String answer;

    private List<RagSource> sources;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<RagRetrievedChunk> retrievedChunks;

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
}
