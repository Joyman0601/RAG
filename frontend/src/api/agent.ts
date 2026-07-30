import request from './request'

export interface AgentStep {
  stepIndex?: number
  action?: string
  thought?: string
  toolName?: string
  toolArguments?: string
  toolResult?: string
  observation?: string
}

export interface ToolResultDTO {
  toolName?: string
  content?: string
  success?: boolean
  errorMessage?: string
  [key: string]: unknown
}

export interface AgentLoopRequest {
  conversationId?: string
  message: string
}

export interface AgentLoopResponse {
  answer: string
  finished: boolean
  stopReason?: string
  steps?: AgentStep[]
  toolResults?: ToolResultDTO[]
  requiresConfirmation: boolean
  confirmationId?: string
  requestId?: string
  conversationId?: string
  toolDebugInfo?: unknown
}

export interface AgentConfirmRequest {
  confirmationId: string
}

export function postAgentLoop(req: AgentLoopRequest): Promise<AgentLoopResponse> {
  return request.post('/agent/loop', req) as unknown as Promise<AgentLoopResponse>
}

export function postAgentConfirm(req: AgentConfirmRequest): Promise<ToolResultDTO> {
  return request.post('/agent/confirm', req) as unknown as Promise<ToolResultDTO>
}
