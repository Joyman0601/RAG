package com.yhl.rag.tool;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

    private final ToolRegistry toolRegistry;

    private final ObjectMapper objectMapper;

    private final Validator validator;

    public ToolExecutionService(ToolRegistry toolRegistry, ObjectMapper objectMapper, Validator validator) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public ToolResult execute(String toolName, JsonNode arguments, ToolExecutionContext context) {
        long startedAt = System.nanoTime();
        String normalizedToolName = null;

        try {
            normalizedToolName = normalizeToolName(toolName);
            ToolExecutor<?> executor = toolRegistry.getTool(normalizedToolName);
            Object request = readAndValidateArguments(executor, arguments);
            Object result = executeTyped(executor, request, context);
            long elapsedMillis = elapsedMillis(startedAt);
            log.info("Tool call finished: requestId={}, toolName={}, elapsedMs={}, success=true",
                    requestId(context),
                    normalizedToolName,
                    elapsedMillis);
            return ToolResult.success(normalizedToolName, result, elapsedMillis);
        } catch (ToolException exception) {
            long elapsedMillis = elapsedMillis(startedAt);
            log.warn("Tool call failed: requestId={}, toolName={}, elapsedMs={}, success=false, errorType={}, message={}",
                    requestId(context),
                    resolveToolName(normalizedToolName, toolName, exception),
                    elapsedMillis,
                    exception.getErrorType(),
                    exception.getMessage(),
                    exception);
            return ToolResult.failure(
                    resolveToolName(normalizedToolName, toolName, exception),
                    exception.getErrorType(),
                    sanitizeErrorMessage(exception),
                    elapsedMillis
            );
        } catch (RuntimeException exception) {
            long elapsedMillis = elapsedMillis(startedAt);
            log.error("Tool call failed unexpectedly: requestId={}, toolName={}, elapsedMs={}, success=false",
                    requestId(context),
                    normalizedToolName == null ? toolName : normalizedToolName,
                    elapsedMillis,
                    exception);
            return ToolResult.failure(
                    normalizedToolName == null ? toolName : normalizedToolName,
                    "TOOL_EXECUTION_ERROR",
                    "tool execution failed",
                    elapsedMillis
            );
        }
    }

    private String normalizeToolName(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            throw new ToolException("TOOL_NAME_REQUIRED", "toolName is required");
        }
        return toolName.trim();
    }

    private Object readAndValidateArguments(ToolExecutor<?> executor, JsonNode arguments) {
        if (arguments == null || arguments.isNull() || !arguments.isObject()) {
            throw new ToolException("TOOL_ARGUMENT_INVALID", "arguments must be a JSON object", executor.getName());
        }

        if ("query_order".equals(executor.getName()) && arguments.has("userId")) {
            throw new ToolException("TOOL_ARGUMENT_FORBIDDEN", "userId is not allowed in arguments", executor.getName());
        }

        Object request;
        try {
            request = objectMapper
                    .readerFor(executor.getRequestClass())
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(arguments);
        } catch (IOException exception) {
            throw new ToolException("TOOL_ARGUMENT_INVALID", "arguments cannot be deserialized to " + executor.getRequestClass().getSimpleName(), executor.getName());
        }

        validateRequest(executor.getName(), request);
        return request;
    }

    private void validateRequest(String toolName, Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .collect(Collectors.joining("; "));
            throw new ToolException("TOOL_ARGUMENT_INVALID", message, toolName);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Object executeTyped(ToolExecutor<?> executor, Object request, ToolExecutionContext context) {
        return ((ToolExecutor<T>) executor).execute((T) request, context);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String requestId(ToolExecutionContext context) {
        return context == null ? null : context.getRequestId();
    }

    private String resolveToolName(String normalizedToolName, String rawToolName, ToolException exception) {
        if (normalizedToolName != null) {
            return normalizedToolName;
        }
        if (exception.getToolName() != null) {
            return exception.getToolName();
        }
        return rawToolName;
    }

    private String sanitizeErrorMessage(ToolException exception) {
        String message = exception.getMessage();
        if (!StringUtils.hasText(message)) {
            return "tool execution failed";
        }
        return message;
    }
}
