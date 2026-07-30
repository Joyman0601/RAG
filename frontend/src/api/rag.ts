import request from './request'

export type RetrievalMode = 'VECTOR' | 'HYBRID' | 'HYBRID_RERANK'

export interface RagSource {
  chunkId?: string
  documentId?: string
  documentName?: string
  filename?: string
  index?: number
  chunkIndex?: number
  score: number
  snippet?: string
  title?: string
  modality?: string
  imageRef?: string
}

export interface RagRetrievedChunk {
  chunkId?: string
  documentId?: string
  filename?: string
  chunkIndex?: number
  content?: string
  score?: number
  debugInfo?: string
  included?: boolean
  [key: string]: unknown
}

export interface RagTokenUsage {
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
}

export interface RagAskRequest {
  question: string
  conversationId?: string
  mode?: RetrievalMode
}

export interface RagAskResponse {
  answer: string
  sources: RagSource[]
  retrievedChunks?: RagRetrievedChunk[]
  tokenUsage?: RagTokenUsage
  effectiveMode?: RetrievalMode
  embeddingDurationMs?: number
  searchDurationMs?: number
}

export interface RagSearchRequest {
  question: string
  mode?: RetrievalMode
}

export interface RagSearchResult {
  chunkId?: string
  documentId?: string
  filename?: string
  chunkIndex?: number
  content?: string
  score: number
  debugInfo?: string
  included?: boolean
  parentId?: string
  modality?: string
  imageRef?: string
}

export function postRagAsk(req: RagAskRequest, debug = false): Promise<RagAskResponse> {
  return request.post(`/rag/ask?debug=${debug}`, req) as unknown as Promise<RagAskResponse>
}

export function postRagSearch(
  req: RagSearchRequest,
  includeBelowThreshold = false,
): Promise<RagSearchResult[]> {
  return request.post(
    `/rag/search?includeBelowThreshold=${includeBelowThreshold}`,
    req,
  ) as unknown as Promise<RagSearchResult[]>
}
