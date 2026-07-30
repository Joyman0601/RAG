package com.yhl.rag.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AgentSafetyCheckResult {

    private boolean passed;

    private List<String> warnings = new ArrayList<>();

    private List<String> errors = new ArrayList<>();

    private List<String> checkedTools = new ArrayList<>();

    private Instant checkedAt;

    public AgentSafetyCheckResult() {
    }

    public AgentSafetyCheckResult(boolean passed, List<String> warnings, List<String> errors, List<String> checkedTools, Instant checkedAt) {
        this.passed = passed;
        this.warnings = warnings;
        this.errors = errors;
        this.checkedTools = checkedTools;
        this.checkedAt = checkedAt;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public List<String> getCheckedTools() {
        return checkedTools;
    }

    public void setCheckedTools(List<String> checkedTools) {
        this.checkedTools = checkedTools;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(Instant checkedAt) {
        this.checkedAt = checkedAt;
    }
}
