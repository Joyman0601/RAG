package com.yhl.rag.llm;

public class LlmGenerationResult {

    private final String answer;

    private final Integer promptTokens;

    private final Integer completionTokens;

    private final Integer totalTokens;

    /** Prompt Caching 命中量（prompt_tokens_details.cached_tokens），不支持时为 null。 */
    private final Integer cachedTokens;

    /** Prompt Caching 写入量（cache_creation_input_tokens），不支持时为 null。 */
    private final Integer cacheCreationInputTokens;

    public LlmGenerationResult(String answer, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(answer, promptTokens, completionTokens, totalTokens, null, null);
    }

    public LlmGenerationResult(
            String answer,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Integer cachedTokens,
            Integer cacheCreationInputTokens
    ) {
        this.answer = answer;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.cachedTokens = cachedTokens;
        this.cacheCreationInputTokens = cacheCreationInputTokens;
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

    public Integer getCachedTokens() {
        return cachedTokens;
    }

    public Integer getCacheCreationInputTokens() {
        return cacheCreationInputTokens;
    }
}
