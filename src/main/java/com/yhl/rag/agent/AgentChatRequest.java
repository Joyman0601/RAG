package com.yhl.rag.agent;

import jakarta.validation.constraints.NotBlank;

public class AgentChatRequest {

    @NotBlank(message = "message cannot be blank")
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
