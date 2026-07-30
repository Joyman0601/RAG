package com.yhl.rag.agent;

import java.time.Instant;

import com.yhl.rag.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    public void logConfirmationCreated(
            String requestId,
            String confirmationId,
            String userId,
            String toolName,
            RiskLevel riskLevel,
            String status,
            String targetSummary,
            Instant createdAt
    ) {
        auditLog.warn("confirmation_created requestId={} confirmationId={} userId={} toolName={} riskLevel={} status={} targetSummary={} createdAt={}",
                requestId,
                confirmationId,
                userId,
                toolName,
                riskLevel,
                status,
                targetSummary,
                createdAt);
    }

    public void logConfirmationExecuted(
            String requestId,
            String confirmationId,
            String userId,
            String toolName,
            RiskLevel riskLevel,
            String status,
            String targetSummary,
            Instant createdAt,
            boolean success,
            long elapsedMs
    ) {
        auditLog.warn("confirmation_executed requestId={} confirmationId={} userId={} toolName={} riskLevel={} status={} targetSummary={} createdAt={} success={} elapsedMs={}",
                requestId,
                confirmationId,
                userId,
                toolName,
                riskLevel,
                status,
                targetSummary,
                createdAt,
                success,
                elapsedMs);
    }

    public void logConfirmationRejected(
            String requestId,
            String confirmationId,
            String userId,
            String toolName,
            RiskLevel riskLevel,
            String status,
            String targetSummary,
            Instant createdAt,
            String reason
    ) {
        auditLog.warn("confirmation_rejected requestId={} confirmationId={} userId={} toolName={} riskLevel={} status={} targetSummary={} createdAt={} reason={}",
                requestId,
                confirmationId,
                userId,
                toolName,
                riskLevel,
                status,
                targetSummary,
                createdAt,
                reason);
    }

    public void logRefundWorkflowTransition(
            String requestId,
            String workflowId,
            String userId,
            RefundWorkflowState fromState,
            RefundWorkflowState toState,
            String event
    ) {
        auditLog.info("refund_workflow_transition requestId={} workflowId={} userId={} fromState={} toState={} event={}",
                requestId,
                workflowId,
                userId,
                fromState,
                toState,
                event);
    }

    public void logRefundSubmitted(
            String requestId,
            String workflowId,
            String userId,
            String confirmationId,
            String orderSummary,
            Instant submittedAt
    ) {
        auditLog.warn("refund_submitted requestId={} workflowId={} userId={} confirmationId={} orderSummary={} submittedAt={}",
                requestId,
                workflowId,
                userId,
                confirmationId,
                orderSummary,
                submittedAt);
    }
}
