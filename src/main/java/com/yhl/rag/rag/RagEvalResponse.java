package com.yhl.rag.rag;

import java.util.List;

public class RagEvalResponse {

    private boolean onlySearch;

    private String caseFile;

    private RagEvalSummary summary;

    private List<RagEvalResult> results;

    public RagEvalResponse() {
    }

    public RagEvalResponse(boolean onlySearch, String caseFile, RagEvalSummary summary, List<RagEvalResult> results) {
        this.onlySearch = onlySearch;
        this.caseFile = caseFile;
        this.summary = summary;
        this.results = results;
    }

    public boolean isOnlySearch() {
        return onlySearch;
    }

    public void setOnlySearch(boolean onlySearch) {
        this.onlySearch = onlySearch;
    }

    public String getCaseFile() {
        return caseFile;
    }

    public void setCaseFile(String caseFile) {
        this.caseFile = caseFile;
    }

    public RagEvalSummary getSummary() {
        return summary;
    }

    public void setSummary(RagEvalSummary summary) {
        this.summary = summary;
    }

    public List<RagEvalResult> getResults() {
        return results;
    }

    public void setResults(List<RagEvalResult> results) {
        this.results = results;
    }
}
