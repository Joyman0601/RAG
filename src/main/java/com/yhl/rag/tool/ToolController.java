package com.yhl.rag.tool;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolExecutionService toolExecutionService;

    public ToolController(ToolExecutionService toolExecutionService) {
        this.toolExecutionService = toolExecutionService;
    }

    @PostMapping("/execute")
    public ToolResult execute(
            @RequestBody(required = false) ToolCallRequest request,
            HttpServletRequest servletRequest
    ) {
        ToolExecutionContext context = new ToolExecutionContext(
                resolveRequestId(servletRequest),
                "user_001",
                "default-department",
                1
        );

        if (request == null) {
            return toolExecutionService.execute(null, null, context);
        }
        return toolExecutionService.execute(request.getToolName(), request.getArguments(), context);
    }

    private String resolveRequestId(HttpServletRequest servletRequest) {
        String requestId = servletRequest.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            return java.util.UUID.randomUUID().toString();
        }
        return requestId.trim();
    }
}
