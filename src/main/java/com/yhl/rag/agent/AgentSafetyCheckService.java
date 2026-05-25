package com.yhl.rag.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.tool.RiskLevel;
import com.yhl.rag.tool.ToolDefinition;
import com.yhl.rag.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentSafetyCheckService {

    private static final Logger log = LoggerFactory.getLogger(AgentSafetyCheckService.class);

    private static final int MAX_REASONABLE_AGENT_STEPS = 5;
    private static final long MAX_REASONABLE_AGENT_DURATION_MS = 60_000;
    private static final int MAX_REASONABLE_RAG_TOP_K = 20;

    private final ToolRegistry toolRegistry;

    private final AgentLoopConfig agentLoopConfig;

    private final AgentSafetyPolicy safetyPolicy;

    private final LlmProperties llmProperties;

    private final RagProperties ragProperties;

    private final Environment environment;

    public AgentSafetyCheckService(
            ToolRegistry toolRegistry,
            AgentLoopConfig agentLoopConfig,
            AgentSafetyPolicy safetyPolicy,
            LlmProperties llmProperties,
            RagProperties ragProperties,
            Environment environment
    ) {
        this.toolRegistry = toolRegistry;
        this.agentLoopConfig = agentLoopConfig;
        this.safetyPolicy = safetyPolicy;
        this.llmProperties = llmProperties;
        this.ragProperties = ragProperties;
        this.environment = environment;
    }

    public AgentSafetyCheckResult checkTools() {
        AgentSafetyCheckResult result = emptyResult();
        checkTools(result);
        return finish(result);
    }

    public AgentSafetyCheckResult checkAgentConfig() {
        AgentSafetyCheckResult result = emptyResult();
        checkAgentConfig(result);
        return finish(result);
    }

    public AgentSafetyCheckResult checkRagConfig() {
        AgentSafetyCheckResult result = emptyResult();
        checkRagConfig(result);
        return finish(result);
    }

    public AgentSafetyCheckResult checkAll() {
        AgentSafetyCheckResult result = emptyResult();
        checkTools(result);
        checkAgentConfig(result);
        checkRagConfig(result);
        return finish(result);
    }

    private void checkTools(AgentSafetyCheckResult result) {
        Map<String, ToolDefinition> definitions = toolRegistry.getDefinitions();
        if (definitions.isEmpty()) {
            result.getErrors().add("No tools are registered.");
            return;
        }

        for (ToolDefinition definition : definitions.values()) {
            String toolName = StringUtils.hasText(definition.getName()) ? definition.getName() : "<unnamed>";
            result.getCheckedTools().add(toolName);
            if (!StringUtils.hasText(definition.getName())) {
                result.getErrors().add("Tool has empty name.");
            }
            if (!StringUtils.hasText(definition.getDescription())) {
                result.getErrors().add("Tool " + toolName + " has empty description.");
            }
            if (definition.getParameterSchema() == null || definition.getParameterSchema().isNull()) {
                result.getErrors().add("Tool " + toolName + " has empty parameterSchema.");
            }
            if (safetyPolicy.isRequireToolPermissionCode() && !StringUtils.hasText(definition.getPermissionCode())) {
                result.getErrors().add("Tool " + toolName + " has empty permissionCode.");
            }
            if (safetyPolicy.isRequireToolRiskLevel() && definition.getRiskLevel() == null) {
                result.getErrors().add("Tool " + toolName + " has empty riskLevel.");
            }
            if (definition.getRiskLevel() == RiskLevel.HIGH) {
                checkHighRiskTool(toolName, result);
            }
        }
    }

    private void checkHighRiskTool(String toolName, AgentSafetyCheckResult result) {
        if (safetyPolicy.isAllowHighRiskAutoExecute()) {
            result.getErrors().add("HIGH risk tool " + toolName + " cannot be configured for auto execution.");
        }
        if (!safetyPolicy.isRequireConfirmationForHighRisk()) {
            result.getErrors().add("HIGH risk tool " + toolName + " must require confirmation.");
        }
        if (agentLoopConfig.isAllowHighRiskTools()) {
            result.getErrors().add("Agent loop cannot auto execute HIGH risk tool " + toolName + ".");
        }
    }

    private void checkAgentConfig(AgentSafetyCheckResult result) {
        if (agentLoopConfig.getMaxSteps() <= 0 || agentLoopConfig.getMaxSteps() > MAX_REASONABLE_AGENT_STEPS) {
            result.getErrors().add("agentLoop.maxSteps must be > 0 and <= " + MAX_REASONABLE_AGENT_STEPS + ".");
        }
        if (agentLoopConfig.getMaxDurationMs() <= 0 || agentLoopConfig.getMaxDurationMs() > MAX_REASONABLE_AGENT_DURATION_MS) {
            result.getErrors().add("agentLoop.maxDurationMs must be > 0 and <= " + MAX_REASONABLE_AGENT_DURATION_MS + ".");
        }
        if (safetyPolicy.isAllowHighRiskAutoExecute()) {
            result.getErrors().add("agent.safety.allowHighRiskAutoExecute must be false.");
        }
        if (safetyPolicy.isLogFullPrompt() && environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            result.getErrors().add("agent.safety.logFullPrompt must be false in production.");
        } else if (safetyPolicy.isLogFullPrompt()) {
            result.getWarnings().add("agent.safety.logFullPrompt is enabled outside production; avoid logging complete prompts.");
        }
        if (safetyPolicy.getMaxAgentSteps() <= 0 || safetyPolicy.getMaxAgentSteps() > MAX_REASONABLE_AGENT_STEPS) {
            result.getErrors().add("agent.safety.maxAgentSteps must be > 0 and <= " + MAX_REASONABLE_AGENT_STEPS + ".");
        }
        if (safetyPolicy.getMaxAgentDurationMs() <= 0 || safetyPolicy.getMaxAgentDurationMs() > MAX_REASONABLE_AGENT_DURATION_MS) {
            result.getErrors().add("agent.safety.maxAgentDurationMs must be > 0 and <= " + MAX_REASONABLE_AGENT_DURATION_MS + ".");
        }
        if (llmProperties.getMaxInputChars() > safetyPolicy.getMaxInputLength()) {
            result.getWarnings().add("llm.maxInputChars is greater than agent.safety.maxInputLength.");
        }
        if (llmProperties.getMaxOutputTokens() > safetyPolicy.getMaxOutputTokens()) {
            result.getWarnings().add("llm.maxOutputTokens is greater than agent.safety.maxOutputTokens.");
        }
    }

    private void checkRagConfig(AgentSafetyCheckResult result) {
        RagProperties.Search search = ragProperties.getSearch();
        if (search == null) {
            result.getErrors().add("rag.search config is missing.");
            return;
        }
        if (search.getTopK() <= 0 || search.getTopK() > MAX_REASONABLE_RAG_TOP_K) {
            result.getErrors().add("rag.search.topK must be > 0 and <= " + MAX_REASONABLE_RAG_TOP_K + ".");
        }
        if (search.getScoreThreshold() < -1 || search.getScoreThreshold() > 1) {
            result.getErrors().add("rag.search.scoreThreshold must be between -1 and 1.");
        }
        result.getWarnings().add("RAG active-chunk filtering is implemented in RagSearchService and should remain enabled.");
        result.getWarnings().add("RAG sources are generated by backend search/tool responses; do not accept model-supplied sources.");
        result.getWarnings().add("RAG permission filtering uses document visibility metadata; verify metadata is present for uploaded documents.");
    }

    private AgentSafetyCheckResult emptyResult() {
        return new AgentSafetyCheckResult(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), Instant.now());
    }

    private AgentSafetyCheckResult finish(AgentSafetyCheckResult result) {
        result.setPassed(result.getErrors().isEmpty());
        log.info("agent_safety_check passed={} checkedTools={} warnings={} errors={} checkedAt={}",
                result.isPassed(),
                result.getCheckedTools().size(),
                result.getWarnings().size(),
                result.getErrors().size(),
                result.getCheckedAt());
        for (String error : result.getErrors()) {
            log.warn("agent_safety_check_error message={}", error);
        }
        return result;
    }
}
