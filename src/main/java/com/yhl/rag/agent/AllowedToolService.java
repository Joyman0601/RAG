package com.yhl.rag.agent;

import java.util.ArrayList;
import java.util.List;

import com.yhl.rag.tool.ToolExecutionContext;
import org.springframework.stereotype.Service;

@Service
public class AllowedToolService {

    public List<String> allowedToolNames(ToolExecutionContext context) {
        List<String> tools = new ArrayList<>();
        if (context.getPermissions().contains("order:query")) {
            tools.add("query_order");
        }
        if (context.getPermissions().contains("order:cancel")) {
            tools.add("cancel_order");
        }
        if (context.getPermissions().contains("knowledge:search")) {
            tools.add("search_knowledge_base");
        }
        return tools;
    }
}
