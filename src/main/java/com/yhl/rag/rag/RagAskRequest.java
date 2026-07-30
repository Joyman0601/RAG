package com.yhl.rag.rag;

import jakarta.validation.constraints.NotBlank;

public class RagAskRequest {

    @NotBlank(message = "question cannot be blank")
    private String question;

    /** 多轮会话标识：传同一 conversationId 即可让 ask 结合历史做指代消解；为空则走单轮，零回归。 */
    private String conversationId;

    // 可选：请求级检索模式覆盖。为 null 时走全局配置 rag.search.mode（零回归）。
    private RagProperties.RetrievalMode mode;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public RagProperties.RetrievalMode getMode() {
        return mode;
    }

    public void setMode(RagProperties.RetrievalMode mode) {
        this.mode = mode;
    }
}
