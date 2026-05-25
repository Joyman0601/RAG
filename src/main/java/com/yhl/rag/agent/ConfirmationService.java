package com.yhl.rag.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.yhl.rag.tool.ToolDefinition;
import com.yhl.rag.tool.ToolExecutionContext;
import com.yhl.rag.tool.ToolExecutionService;
import com.yhl.rag.tool.ToolRegistry;
import com.yhl.rag.tool.ToolResult;
import com.yhl.rag.tool.ValidatedToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);

    private final ConcurrentMap<String, PendingConfirmation> confirmations = new ConcurrentHashMap<>();

    private final ToolExecutionService toolExecutionService;

    private final ToolRegistry toolRegistry;

    private final AuditLogService auditLogService;

    public ConfirmationService(ToolExecutionService toolExecutionService, ToolRegistry toolRegistry, AuditLogService auditLogService) {
        this.toolExecutionService = toolExecutionService;
        this.toolRegistry = toolRegistry;
        this.auditLogService = auditLogService;
    }

    public PendingConfirmation createPendingConfirmation(
            ValidatedToolCall validatedToolCall,
            ToolExecutionContext context,
            String summary
    ) {
        Instant now = Instant.now();
        PendingConfirmation pending = new PendingConfirmation();
        pending.setConfirmationId(UUID.randomUUID().toString());
        pending.setUserId(context.getUserId());
        pending.setToolName(validatedToolCall.getToolName());
        pending.setValidatedArguments(validatedToolCall.getValidatedArguments());
        pending.setRiskLevel(validatedToolCall.getDefinition().getRiskLevel());
        pending.setSummary(summary);
        pending.setStatus(PendingConfirmationStatus.PENDING);
        pending.setCreatedAt(now);
        pending.setExpiresAt(now.plus(CONFIRMATION_TTL));
        pending.setRequestId(context.getRequestId());
        confirmations.put(pending.getConfirmationId(), pending);

        auditLogService.logConfirmationCreated(
                pending.getRequestId(),
                pending.getConfirmationId(),
                pending.getUserId(),
                pending.getToolName(),
                pending.getRiskLevel(),
                pending.getStatus().name(),
                pending.getSummary(),
                pending.getCreatedAt()
        );
        return pending;
    }

    public ToolResult confirm(String confirmationId, ToolExecutionContext context) {
        PendingConfirmation pending = confirmations.get(confirmationId);
        if (pending == null) {
            return ToolResult.failure(null, AgentErrorCode.BUSINESS_REJECTED.name(), "confirmation not found", 0);
        }

        if (!pending.getUserId().equals(context.getUserId())) {
            auditRejected(pending, "USER_MISMATCH");
            return ToolResult.failure(pending.getToolName(), AgentErrorCode.PERMISSION_DENIED.name(), "current user is not allowed to confirm this operation", 0);
        }

        if (Instant.now().isAfter(pending.getExpiresAt())) {
            pending.setStatus(PendingConfirmationStatus.EXPIRED);
            auditRejected(pending, "EXPIRED");
            return ToolResult.failure(pending.getToolName(), AgentErrorCode.CONFIRMATION_EXPIRED.name(), "confirmation expired", 0);
        }

        if (pending.getStatus() != PendingConfirmationStatus.PENDING) {
            auditRejected(pending, "NOT_PENDING");
            return ToolResult.failure(pending.getToolName(), AgentErrorCode.BUSINESS_REJECTED.name(), "confirmation is no longer pending", 0);
        }

        ToolDefinition definition = toolRegistry.findDefinition(pending.getToolName()).orElse(null);
        if (definition == null || !hasPermission(context, definition.getPermissionCode())) {
            auditRejected(pending, "PERMISSION_DENIED");
            return ToolResult.failure(pending.getToolName(), AgentErrorCode.PERMISSION_DENIED.name(), "current user is not allowed to call this tool", 0);
        }

        ToolExecutionContext confirmedContext = confirmedContext(context);
        ToolResult result = toolExecutionService.execute(
            pending.getToolName(),
            pending.getValidatedArguments(),
            confirmedContext
        );
        pending.setStatus(PendingConfirmationStatus.EXECUTED);
        auditLogService.logConfirmationExecuted(
                context.getRequestId(),
                pending.getConfirmationId(),
                pending.getUserId(),
                pending.getToolName(),
                pending.getRiskLevel(),
                pending.getStatus().name(),
                pending.getSummary(),
                pending.getCreatedAt(),
                result.isSuccess(),
                result.getElapsedMs()
        );
        return result;
    }

    private ToolExecutionContext confirmedContext(ToolExecutionContext context) {
        ToolExecutionContext confirmedContext = new ToolExecutionContext(
                context.getRequestId(),
                context.getUserId(),
                context.getDepartment(),
                context.getPermissionLevel(),
                context.getRoles(),
                context.getPermissions()
        );
        confirmedContext.setConfirmedHighRiskExecution(true);
        return confirmedContext;
    }

    private boolean hasPermission(ToolExecutionContext context, String permissionCode) {
        return !StringUtils.hasText(permissionCode)
                || context.getPermissions().contains(permissionCode);
    }

    private void auditRejected(PendingConfirmation pending, String reason) {
        auditLogService.logConfirmationRejected(
                pending.getRequestId(),
                pending.getConfirmationId(),
                pending.getUserId(),
                pending.getToolName(),
                pending.getRiskLevel(),
                pending.getStatus().name(),
                pending.getSummary(),
                pending.getCreatedAt(),
                reason
        );
    }
}
