package com.yhl.rag.demo;

public class LlmQuotaExceededException extends RuntimeException {

    private final long limit;

    public LlmQuotaExceededException(long limit) {
        super("演示额度已用完（日上限 " + limit + " 次 chat 调用），请查看录屏");
        this.limit = limit;
    }

    public long getLimit() {
        return limit;
    }
}
