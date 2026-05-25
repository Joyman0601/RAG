package com.yhl.rag.rag;

import java.util.List;

public class RagEvalCase {

    private String id;

    private String question;

    private String expectedAnswer;

    private String expectedDocumentId;

    private List<String> expectedKeywords;

    public RagEvalCase() {
    }

    public RagEvalCase(
            String id,
            String question,
            String expectedAnswer,
            String expectedDocumentId,
            List<String> expectedKeywords
    ) {
        this.id = id;
        this.question = question;
        this.expectedAnswer = expectedAnswer;
        this.expectedDocumentId = expectedDocumentId;
        this.expectedKeywords = expectedKeywords;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getExpectedAnswer() {
        return expectedAnswer;
    }

    public void setExpectedAnswer(String expectedAnswer) {
        this.expectedAnswer = expectedAnswer;
    }

    public String getExpectedDocumentId() {
        return expectedDocumentId;
    }

    public void setExpectedDocumentId(String expectedDocumentId) {
        this.expectedDocumentId = expectedDocumentId;
    }

    public List<String> getExpectedKeywords() {
        return expectedKeywords;
    }

    public void setExpectedKeywords(List<String> expectedKeywords) {
        this.expectedKeywords = expectedKeywords;
    }
}
