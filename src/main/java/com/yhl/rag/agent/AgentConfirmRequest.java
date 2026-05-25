package com.yhl.rag.agent;

import jakarta.validation.constraints.NotBlank;

public class AgentConfirmRequest {

    @NotBlank(message = "confirmationId cannot be blank")
    private String confirmationId;

    public String getConfirmationId() {
        return confirmationId;
    }

    public void setConfirmationId(String confirmationId) {
        this.confirmationId = confirmationId;
    }
}
