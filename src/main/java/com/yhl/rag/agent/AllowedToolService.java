package com.yhl.rag.agent;

import java.util.ArrayList;
import java.util.List;

import com.yhl.rag.tool.ToolExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AllowedToolService {

    private final AgentToolRolloutService rolloutService;

    @Autowired
    public AllowedToolService(AgentToolRolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    public AllowedToolService() {
        this(new AgentToolRolloutService());
    }

    public List<String> allowedToolNames(ToolExecutionContext context) {
        List<String> tools = new ArrayList<>();
        if (context.getPermissions().contains("order:query") && rolloutService.isVisible("query_order", context)) {
            tools.add("query_order");
        }
        if (context.getPermissions().contains("order:cancel") && rolloutService.isVisible("cancel_order", context)) {
            tools.add("cancel_order");
        }
        if (context.getPermissions().contains("knowledge:search") && rolloutService.isVisible("search_knowledge_base", context)) {
            tools.add("search_knowledge_base");
        }
        return tools;
    }
}
