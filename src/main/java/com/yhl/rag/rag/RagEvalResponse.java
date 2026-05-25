package com.yhl.rag.rag;

import java.util.List;

public class RagEvalResponse {

    private int total;

    private int passed;

    private int failed;

    private double passRate;

    private boolean onlySearch;

    private List<RagEvalResult> results;

    public RagEvalResponse() {
    }

    public RagEvalResponse(int total, int passed, int failed, double passRate, boolean onlySearch, List<RagEvalResult> results) {
        this.total = total;
        this.passed = passed;
        this.failed = failed;
        this.passRate = passRate;
        this.onlySearch = onlySearch;
        this.results = results;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPassed() {
        return passed;
    }

    public void setPassed(int passed) {
        this.passed = passed;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public double getPassRate() {
        return passRate;
    }

    public void setPassRate(double passRate) {
        this.passRate = passRate;
    }

    public boolean isOnlySearch() {
        return onlySearch;
    }

    public void setOnlySearch(boolean onlySearch) {
        this.onlySearch = onlySearch;
    }

    public List<RagEvalResult> getResults() {
        return results;
    }

    public void setResults(List<RagEvalResult> results) {
        this.results = results;
    }
}
