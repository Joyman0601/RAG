package com.yhl.rag.agent;

import com.yhl.rag.tool.ToolResult;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentChatService agentChatService;

    private final ConfirmationService confirmationService;

    private final AgentLoopService agentLoopService;

    private final RefundWorkflowService refundWorkflowService;

    private final AgentSafetyCheckService agentSafetyCheckService;

    public AgentController(
            AgentChatService agentChatService,
            ConfirmationService confirmationService,
            AgentLoopService agentLoopService,
            RefundWorkflowService refundWorkflowService,
            AgentSafetyCheckService agentSafetyCheckService
    ) {
        this.agentChatService = agentChatService;
        this.confirmationService = confirmationService;
        this.agentLoopService = agentLoopService;
        this.refundWorkflowService = refundWorkflowService;
        this.agentSafetyCheckService = agentSafetyCheckService;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest request) {
        return agentChatService.chat(request.getConversationId(), request.getMessage());
    }

    @PostMapping("/confirm")
    public ToolResult confirm(@Valid @RequestBody AgentConfirmRequest request) {
        return confirmationService.confirm(request.getConfirmationId(), agentChatService.mockContext());
    }

    @PostMapping("/loop")
    public AgentLoopResponse loop(@Valid @RequestBody AgentChatRequest request) {
        return agentLoopService.run(request.getConversationId(), request.getMessage(), agentChatService.mockContext());
    }

    @PostMapping("/refund")
    public RefundWorkflowResponse refund(@Valid @RequestBody RefundWorkflowRequest request) {
        return refundWorkflowService.startOrContinue(
                request.getConversationId(),
                agentChatService.mockContext().getUserId(),
                request.getMessage()
        );
    }

    @org.springframework.web.bind.annotation.GetMapping("/safety/check")
    public AgentSafetyCheckResult safetyCheck() {
        return agentSafetyCheckService.checkAll();
    }
}
