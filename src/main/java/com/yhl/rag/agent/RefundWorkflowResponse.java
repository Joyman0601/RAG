package com.yhl.rag.agent;

import java.util.List;

public class RefundWorkflowResponse {

    private String workflowId;

    private RefundWorkflowState state;

    private String answer;

    private boolean requiresConfirmation;

    private String confirmationId;

    private String requestId;

    private List<AgentStep> steps;

    public RefundWorkflowResponse() {
    }

    public RefundWorkflowResponse(
            String workflowId,
            RefundWorkflowState state,
            String answer,
            boolean requiresConfirmation,
            String confirmationId,
            String requestId,
            List<AgentStep> steps
    ) {
        this.workflowId = workflowId;
        this.state = state;
        this.answer = answer;
        this.requiresConfirmation = requiresConfirmation;
        this.confirmationId = confirmationId;
        this.requestId = requestId;
        this.steps = steps;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public RefundWorkflowState getState() {
        return state;
    }

    public void setState(RefundWorkflowState state) {
        this.state = state;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getConfirmationId() {
        return confirmationId;
    }

    public void setConfirmationId(String confirmationId) {
        this.confirmationId = confirmationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentStep> steps) {
        this.steps = steps;
    }
}
