package com.yhl.rag.tool;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.agent.AgentErrorCode;
import com.yhl.rag.agent.AgentSafetyPolicy;
import com.yhl.rag.observability.MetricsService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

    private final ToolRegistry toolRegistry;

    private final ObjectMapper objectMapper;

    private final Validator validator;

    private final AgentSafetyPolicy safetyPolicy;
    private final MetricsService metricsService;

    public ToolExecutionService(ToolRegistry toolRegistry, ObjectMapper objectMapper, Validator validator) {
        this(toolRegistry, objectMapper, validator, new AgentSafetyPolicy(), new MetricsService());
    }

    public ToolExecutionService(ToolRegistry toolRegistry, ObjectMapper objectMapper, Validator validator, AgentSafetyPolicy safetyPolicy) {
        this(toolRegistry, objectMapper, validator, safetyPolicy, new MetricsService());
    }

    @Autowired
    public ToolExecutionService(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            Validator validator,
            AgentSafetyPolicy safetyPolicy,
            MetricsService metricsService
    ) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.safetyPolicy = safetyPolicy;
        this.metricsService = metricsService;
    }

    public ToolResult execute(String toolName, JsonNode arguments, ToolExecutionContext context) {
        long startedAt = System.nanoTime();
        String normalizedToolName = null;

        try {
            normalizedToolName = normalizeToolName(toolName);
            ToolExecutor<?> executor = toolRegistry.getTool(normalizedToolName);
            checkHighRiskConfirmation(executor.getDefinition(), context);
            checkPermission(executor.getDefinition(), context);
            Object request = readAndValidateArguments(executor, arguments);
            Object result = executeTyped(executor, request, context);
            long elapsedMillis = elapsedMillis(startedAt);
            log.info("tool_call requestId={} toolName={} riskLevel={} success=true elapsedMs={} errorCode={} resultSummary={}",
                    requestId(context),
                    normalizedToolName,
                    executor.getDefinition().getRiskLevel(),
                    elapsedMillis,
                    null,
                    summarizeResult(result));
            return ToolResult.success(normalizedToolName, result, elapsedMillis);
        } catch (ToolException exception) {
            long elapsedMillis = elapsedMillis(startedAt);
            String errorCode = normalizeErrorCode(exception.getErrorType());
            metricsService.recordToolFailure();
            log.warn("tool_call requestId={} toolName={} riskLevel={} success=false elapsedMs={} errorCode={} message={}",
                    requestId(context),
                    resolveToolName(normalizedToolName, toolName, exception),
                    riskLevel(resolveToolName(normalizedToolName, toolName, exception)),
                    elapsedMillis,
                    errorCode,
                    exception.getMessage(),
                    exception);
            return ToolResult.failure(
                    resolveToolName(normalizedToolName, toolName, exception),
                    errorCode,
                    sanitizeErrorMessage(exception),
                    elapsedMillis
            );
        } catch (RuntimeException exception) {
            long elapsedMillis = elapsedMillis(startedAt);
            metricsService.recordToolFailure();
            log.error("tool_call requestId={} toolName={} riskLevel={} success=false elapsedMs={} errorCode={}",
                    requestId(context),
                    normalizedToolName == null ? toolName : normalizedToolName,
                    riskLevel(normalizedToolName == null ? toolName : normalizedToolName),
                    elapsedMillis,
                    AgentErrorCode.TOOL_EXECUTION_FAILED,
                    exception);
            return ToolResult.failure(
                    normalizedToolName == null ? toolName : normalizedToolName,
                    AgentErrorCode.TOOL_EXECUTION_FAILED.name(),
                    "tool execution failed",
                    elapsedMillis
            );
        }
    }

    public ValidatedToolCall validate(String toolName, JsonNode arguments, ToolExecutionContext context) {
        String normalizedToolName = normalizeToolName(toolName);
        ToolExecutor<?> executor = toolRegistry.getTool(normalizedToolName);
        checkPermission(executor.getDefinition(), context);
        readAndValidateArguments(executor, arguments);
        JsonNode snapshot = arguments == null ? null : arguments.deepCopy();
        return new ValidatedToolCall(normalizedToolName, executor.getDefinition(), snapshot);
    }

    private String normalizeToolName(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            throw new ToolException(AgentErrorCode.VALIDATION_ERROR.name(), "toolName is required");
        }
        return toolName.trim();
    }

    private Object readAndValidateArguments(ToolExecutor<?> executor, JsonNode arguments) {
        if (arguments == null || arguments.isNull() || !arguments.isObject()) {
            throw new ToolException(AgentErrorCode.VALIDATION_ERROR.name(), "arguments must be a JSON object", executor.getName());
        }

        String forbiddenArgument = findForbiddenArgument(executor.getName(), arguments);
        if (forbiddenArgument != null) {
            throw new ToolException(AgentErrorCode.PERMISSION_DENIED.name(), forbiddenArgument + " is not allowed in tool arguments", executor.getName());
        }

        Object request;
        try {
            request = objectMapper
                    .readerFor(executor.getRequestClass())
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(arguments);
        } catch (IOException exception) {
            throw new ToolException(AgentErrorCode.VALIDATION_ERROR.name(), "arguments format is invalid", executor.getName());
        }

        validateRequest(executor.getName(), request);
        return request;
    }

    private void checkPermission(ToolDefinition definition, ToolExecutionContext context) {
        String permissionCode = definition.getPermissionCode();
        if (!StringUtils.hasText(permissionCode)) {
            return;
        }
        if (context == null || context.getPermissions() == null || !context.getPermissions().contains(permissionCode)) {
            throw new ToolException(AgentErrorCode.PERMISSION_DENIED.name(), "current user is not allowed to call this tool", definition.getName());
        }
    }

    private void checkHighRiskConfirmation(ToolDefinition definition, ToolExecutionContext context) {
        if (definition.getRiskLevel() != RiskLevel.HIGH) {
            return;
        }
        boolean confirmationRequired = safetyPolicy.isRequireConfirmationForHighRisk()
                || !safetyPolicy.isAllowHighRiskAutoExecute();
        if (confirmationRequired && (context == null || !context.isConfirmedHighRiskExecution())) {
            throw new ToolException(
                    AgentErrorCode.CONFIRMATION_REQUIRED.name(),
                    "high risk tool requires confirmation",
                    definition.getName()
            );
        }
    }

    private void validateRequest(String toolName, Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .collect(Collectors.joining("; "));
            throw new ToolException(AgentErrorCode.VALIDATION_ERROR.name(), message, toolName);
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

    private String normalizeErrorCode(String errorType) {
        if (!StringUtils.hasText(errorType)) {
            return AgentErrorCode.TOOL_EXECUTION_FAILED.name();
        }
        if ("TOOL_NOT_FOUND".equals(errorType)) {
            return AgentErrorCode.UNKNOWN_TOOL.name();
        }
        if ("ORDER_NOT_FOUND".equals(errorType)) {
            return AgentErrorCode.BUSINESS_REJECTED.name();
        }
        return errorType;
    }

    private String findForbiddenArgument(String toolName, JsonNode arguments) {
        Set<String> forbiddenKeys = "search_knowledge_base".equals(toolName)
                ? Set.of("userId", "tenantId", "role", "roleId", "roleIds", "departmentId", "departmentIds", "visibility", "scoreThreshold")
                : Set.of("userId", "tenantId", "topK", "scoreThreshold", "role", "roleId", "roleIds", "departmentId", "departmentIds", "visibility");
        for (String forbiddenKey : forbiddenKeys) {
            if (arguments.has(forbiddenKey)) {
                return forbiddenKey;
            }
        }
        return null;
    }

    private RiskLevel riskLevel(String toolName) {
        return toolRegistry.findDefinition(toolName)
                .map(ToolDefinition::getRiskLevel)
                .orElse(null);
    }

    private String summarizeResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof QueryOrderToolResult order) {
            return "orderId=" + order.getOrderId()
                    + ",status=" + order.getStatus()
                    + ",amount=" + order.getAmount();
        }
        if (result instanceof SearchKnowledgeToolResult knowledge) {
            return "answerable=" + knowledge.isAnswerable()
                    + ",retrievedCount=" + knowledge.getRetrievedCount()
                    + ",sourceCount=" + (knowledge.getSources() == null ? 0 : knowledge.getSources().size());
        }
        String typeName = result.getClass().getSimpleName();
        return "type=" + typeName + ",stringLength=" + String.valueOf(result).length();
    }
}
