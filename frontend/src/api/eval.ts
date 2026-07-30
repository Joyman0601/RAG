import request from './request'
import type { RagSource } from './rag'

export interface RagEvalSummary {
  total: number
  averageHitAtK: number
  averageRecallAtK: number
  averageMrr: number
  averageLatencyMs: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
}

export interface RagEvalResult {
  caseId: string
  question: string
  expectedSourceChunkIds?: string[]
  expectedSourceDocumentIds?: string[]
  hitAtK: boolean
  recallAtK: number
  mrr: number
  hitChunkIds?: string[]
  answer?: string
  sources?: RagSource[]
  hasAnswer: boolean
  noAnswerFallback: boolean
  sourcesContainExpectedDocuments: boolean
  answerContainsExpectedPhrase: boolean
  latencyMs: number
  success: boolean
  errorCode?: string
  errorMessage?: string
}

export interface RagEvalResponse {
  onlySearch: boolean
  caseFile?: string
  summary: RagEvalSummary
  results: RagEvalResult[]
}

export function getRagEval(onlySearch = false): Promise<RagEvalResponse> {
  return request.get(`/rag/eval?onlySearch=${onlySearch}`) as unknown as Promise<RagEvalResponse>
}
