package com.yhl.rag.rag;

import java.util.List;

public class RagEvalResult {

    private String caseId;

    private String question;

    private List<RagSearchResult> retrievedChunks;

    private List<String> expectedSourceChunkIds;

    private List<String> expectedSourceDocumentIds;

    private boolean hitAtK;

    private double recallAtK;

    private double mrr;

    private List<String> hitChunkIds;

    private String answer;

    private List<RagSource> sources;

    private boolean hasAnswer;

    private boolean noAnswerFallback;

    private boolean sourcesContainExpectedDocuments;

    private boolean answerContainsExpectedPhrase;

    private long latencyMs;

    private RagTokenUsage tokenUsage;

    private boolean success = true;

    private String errorCode;

    private String errorMessage;

    public RagEvalResult() {
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<RagSearchResult> getRetrievedChunks() {
        return retrievedChunks;
    }

    public void setRetrievedChunks(List<RagSearchResult> retrievedChunks) {
        this.retrievedChunks = retrievedChunks;
    }

    public List<String> getExpectedSourceChunkIds() {
        return expectedSourceChunkIds;
    }

    public void setExpectedSourceChunkIds(List<String> expectedSourceChunkIds) {
        this.expectedSourceChunkIds = expectedSourceChunkIds;
    }

    public List<String> getExpectedSourceDocumentIds() {
        return expectedSourceDocumentIds;
    }

    public void setExpectedSourceDocumentIds(List<String> expectedSourceDocumentIds) {
        this.expectedSourceDocumentIds = expectedSourceDocumentIds;
    }

    public boolean isHitAtK() {
        return hitAtK;
    }

    public void setHitAtK(boolean hitAtK) {
        this.hitAtK = hitAtK;
    }

    public double getRecallAtK() {
        return recallAtK;
    }

    public void setRecallAtK(double recallAtK) {
        this.recallAtK = recallAtK;
    }

    public double getMrr() {
        return mrr;
    }

    public void setMrr(double mrr) {
        this.mrr = mrr;
    }

    public List<String> getHitChunkIds() {
        return hitChunkIds;
    }

    public void setHitChunkIds(List<String> hitChunkIds) {
        this.hitChunkIds = hitChunkIds;
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

    public boolean isHasAnswer() {
        return hasAnswer;
    }

    public void setHasAnswer(boolean hasAnswer) {
        this.hasAnswer = hasAnswer;
    }

    public boolean isNoAnswerFallback() {
        return noAnswerFallback;
    }

    public void setNoAnswerFallback(boolean noAnswerFallback) {
        this.noAnswerFallback = noAnswerFallback;
    }

    public boolean isSourcesContainExpectedDocuments() {
        return sourcesContainExpectedDocuments;
    }

    public void setSourcesContainExpectedDocuments(boolean sourcesContainExpectedDocuments) {
        this.sourcesContainExpectedDocuments = sourcesContainExpectedDocuments;
    }

    public boolean isAnswerContainsExpectedPhrase() {
        return answerContainsExpectedPhrase;
    }

    public void setAnswerContainsExpectedPhrase(boolean answerContainsExpectedPhrase) {
        this.answerContainsExpectedPhrase = answerContainsExpectedPhrase;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public RagTokenUsage getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(RagTokenUsage tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
