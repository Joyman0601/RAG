package com.yhl.rag.rag;

import jakarta.validation.constraints.NotBlank;

public class RagSearchRequest {

    @NotBlank(message = "question cannot be blank")
    private String question;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
