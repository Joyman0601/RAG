package com.yhl.rag.rag;

import jakarta.validation.constraints.NotBlank;

public class RagSearchRequest {

    @NotBlank(message = "question cannot be blank")
    private String question;

    // 可选：请求级检索模式覆盖。为 null 时走全局配置 rag.search.mode（零回归）。
    // 前端 Ask 页三模式并列对比时会分别传 VECTOR / HYBRID / HYBRID_RERANK。
    private RagProperties.RetrievalMode mode;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public RagProperties.RetrievalMode getMode() {
        return mode;
    }

    public void setMode(RagProperties.RetrievalMode mode) {
        this.mode = mode;
    }
}
