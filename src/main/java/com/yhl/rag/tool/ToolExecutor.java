package com.yhl.rag.tool;

public interface ToolExecutor<T> {

    String getName();

    ToolDefinition getDefinition();

    Class<T> getRequestClass();

    Object execute(T request, ToolExecutionContext context);
}
