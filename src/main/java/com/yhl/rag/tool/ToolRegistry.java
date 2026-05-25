package com.yhl.rag.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.yhl.rag.agent.AgentErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final Map<String, ToolExecutor<?>> executors = new LinkedHashMap<>();

    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    public ToolRegistry(List<ToolExecutor<?>> executors) {
        for (ToolExecutor<?> executor : executors) {
            ToolExecutor<?> previous = this.executors.put(executor.getName(), executor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate tool executor: " + executor.getName());
            }

            ToolDefinition definition = executor.getDefinition();
            if (definition == null) {
                throw new IllegalStateException("Missing tool definition: " + executor.getName());
            }
            if (!executor.getName().equals(definition.getName())) {
                throw new IllegalStateException("Tool definition name mismatch: " + executor.getName());
            }
            this.definitions.put(definition.getName(), definition);
        }
    }

    public ToolExecutor<?> getTool(String toolName) {
        ToolExecutor<?> executor = executors.get(toolName);
        if (executor == null) {
            throw new ToolException(AgentErrorCode.UNKNOWN_TOOL.name(), "tool not found", toolName, HttpStatus.NOT_FOUND);
        }
        return executor;
    }

    public Optional<ToolExecutor<?>> findExecutor(String toolName) {
        return Optional.ofNullable(executors.get(toolName));
    }

    public Optional<ToolDefinition> findDefinition(String toolName) {
        return Optional.ofNullable(definitions.get(toolName));
    }

    public Map<String, ToolDefinition> getDefinitions() {
        return Map.copyOf(definitions);
    }
}
