package com.yhl.rag.agent;

import com.fasterxml.jackson.databind.JsonNode;

public class AgentToolDecision {

    private String answer;

    private ToolCall toolCall;

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public ToolCall getToolCall() {
        return toolCall;
    }

    public void setToolCall(ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    public static class ToolCall {

        private String toolName;

        private JsonNode arguments;

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public JsonNode getArguments() {
            return arguments;
        }

        public void setArguments(JsonNode arguments) {
            this.arguments = arguments;
        }
    }
}
