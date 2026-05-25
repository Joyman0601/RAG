package com.yhl.rag.agent;

import jakarta.validation.constraints.NotBlank;

public class RefundWorkflowRequest {

    private String conversationId;

    @NotBlank(message = "message cannot be blank")
    private String message;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
