package com.yhl.rag.rag;

import java.util.List;

public class RagEvalCase {

    private String caseId;

    private String question;

    private String expectedAnswer;

    private List<String> expectedSourceChunkIds;

    private List<String> expectedSourceDocumentIds;

    private String tenantId;

    private String userId;

    private List<String> tags;

    public RagEvalCase() {
    }

    public RagEvalCase(
            String caseId,
            String question,
            String expectedAnswer,
            List<String> expectedSourceChunkIds,
            List<String> expectedSourceDocumentIds,
            String tenantId,
            String userId,
            List<String> tags
    ) {
        this.caseId = caseId;
        this.question = question;
        this.expectedAnswer = expectedAnswer;
        this.expectedSourceChunkIds = expectedSourceChunkIds;
        this.expectedSourceDocumentIds = expectedSourceDocumentIds;
        this.tenantId = tenantId;
        this.userId = userId;
        this.tags = tags;
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

    public String getExpectedAnswer() {
        return expectedAnswer;
    }

    public void setExpectedAnswer(String expectedAnswer) {
        this.expectedAnswer = expectedAnswer;
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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }
}
