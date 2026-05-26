package com.yhl.rag.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.tool.RiskLevel;
import com.yhl.rag.tool.ToolExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ShadowToolDecisionService {

    private static final int MAX_DECISIONS = 200;

    private final Deque<ShadowToolDecision> decisions = new ArrayDeque<>();

    private final ObjectMapper objectMapper;

    @Autowired
    public ShadowToolDecisionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ShadowToolDecisionService() {
        this(new ObjectMapper());
    }

    public synchronized ShadowToolDecision record(
            ToolExecutionContext context,
            String toolName,
            JsonNode arguments,
            String validationResult,
            RiskLevel riskLevel,
            ShadowToolPolicyDecision policyDecision,
            String blockedReason,
            String model,
            long latencyMs
    ) {
        ShadowToolDecision decision = new ShadowToolDecision(
                context == null ? null : context.getRequestId(),
                context == null ? null : context.getTenantId(),
                context == null ? null : context.getUserId(),
                toolName,
                hashArguments(arguments),
                validationResult,
                riskLevel,
                policyDecision,
                blockedReason,
                model,
                latencyMs,
                Instant.now()
        );
        decisions.addFirst(decision);
        while (decisions.size() > MAX_DECISIONS) {
            decisions.removeLast();
        }
        return decision;
    }

    public synchronized List<ShadowToolDecision> recent(int limit) {
        int cappedLimit = limit <= 0 ? 50 : Math.min(limit, MAX_DECISIONS);
        return new ArrayList<>(decisions).stream()
                .limit(cappedLimit)
                .toList();
    }

    private String hashArguments(JsonNode arguments) {
        String canonical;
        try {
            canonical = arguments == null ? "{}" : objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            canonical = arguments == null ? "{}" : arguments.toString();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
