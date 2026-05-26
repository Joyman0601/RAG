package com.yhl.rag.rag;

public class RagEvalRunRequest {

    private String caseFile;

    private boolean onlySearch;

    public String getCaseFile() {
        return caseFile;
    }

    public void setCaseFile(String caseFile) {
        this.caseFile = caseFile;
    }

    public boolean isOnlySearch() {
        return onlySearch;
    }

    public void setOnlySearch(boolean onlySearch) {
        this.onlySearch = onlySearch;
    }
}
