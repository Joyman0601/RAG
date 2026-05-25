package com.yhl.rag.llm;

public class LlmGenerationResult {

    private final String answer;

    private final Integer promptTokens;

    private final Integer completionTokens;

    private final Integer totalTokens;

    public LlmGenerationResult(String answer, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this.answer = answer;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public String getAnswer() {
        return answer;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }
}
