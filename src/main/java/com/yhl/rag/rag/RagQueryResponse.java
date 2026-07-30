package com.yhl.rag.rag;

import java.util.List;

public class RagQueryResponse {

    private String answer;

    private List<RagSource> sources;

    public RagQueryResponse() {
    }

    public RagQueryResponse(String answer, List<RagSource> sources) {
        this.answer = answer;
        this.sources = sources;
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
}
