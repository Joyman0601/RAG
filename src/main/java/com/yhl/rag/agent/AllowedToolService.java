package com.yhl.rag.agent;

import java.util.List;

import com.yhl.rag.tool.ToolExecutionContext;
import org.springframework.stereotype.Service;

@Service
public class AllowedToolService {

    public List<String> allowedToolNames(ToolExecutionContext context) {
        return List.of("query_order");
    }
}
