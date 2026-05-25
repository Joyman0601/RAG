package com.yhl.rag.rag;

import java.util.List;

public class RagEvalResult {

    private String id;

    private String question;

    private String expectedAnswer;

    private String actualAnswer;

    private String expectedDocumentId;

    private List<String> retrievedDocumentIds;

    private boolean hitExpectedDocument;

    private List<String> expectedKeywords;

    private boolean keywordMatched;

    private List<RagSource> sources;

    private boolean passed;

    public RagEvalResult() {
    }

    public RagEvalResult(
            String id,
            String question,
            String expectedAnswer,
            String actualAnswer,
            String expectedDocumentId,
            List<String> retrievedDocumentIds,
            boolean hitExpectedDocument,
            List<String> expectedKeywords,
            boolean keywordMatched,
            List<RagSource> sources,
            boolean passed
    ) {
        this.id = id;
        this.question = question;
        this.expectedAnswer = expectedAnswer;
        this.actualAnswer = actualAnswer;
        this.expectedDocumentId = expectedDocumentId;
        this.retrievedDocumentIds = retrievedDocumentIds;
        this.hitExpectedDocument = hitExpectedDocument;
        this.expectedKeywords = expectedKeywords;
        this.keywordMatched = keywordMatched;
        this.sources = sources;
        this.passed = passed;
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

    public String getActualAnswer() {
        return actualAnswer;
    }

    public void setActualAnswer(String actualAnswer) {
        this.actualAnswer = actualAnswer;
    }

    public String getExpectedDocumentId() {
        return expectedDocumentId;
    }

    public void setExpectedDocumentId(String expectedDocumentId) {
        this.expectedDocumentId = expectedDocumentId;
    }

    public List<String> getRetrievedDocumentIds() {
        return retrievedDocumentIds;
    }

    public void setRetrievedDocumentIds(List<String> retrievedDocumentIds) {
        this.retrievedDocumentIds = retrievedDocumentIds;
    }

    public boolean isHitExpectedDocument() {
        return hitExpectedDocument;
    }

    public void setHitExpectedDocument(boolean hitExpectedDocument) {
        this.hitExpectedDocument = hitExpectedDocument;
    }

    public List<String> getExpectedKeywords() {
        return expectedKeywords;
    }

    public void setExpectedKeywords(List<String> expectedKeywords) {
        this.expectedKeywords = expectedKeywords;
    }

    public boolean isKeywordMatched() {
        return keywordMatched;
    }

    public void setKeywordMatched(boolean keywordMatched) {
        this.keywordMatched = keywordMatched;
    }

    public List<RagSource> getSources() {
        return sources;
    }

    public void setSources(List<RagSource> sources) {
        this.sources = sources;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }
}
