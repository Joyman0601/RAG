import request from './request'

export type DocumentStatus = 'PROCESSING' | 'READY' | 'DELETED' | 'FAILED' | string
export type DocumentVisibility = 'PUBLIC' | 'DEPARTMENT' | 'PRIVATE' | string

export interface DocumentInfo {
  id: string
  filename: string
  contentType?: string
  size: number
  createdAt?: string
  tenantId?: string
  ownerId?: string
  departmentId?: string
  visibility?: DocumentVisibility
  allowedUserIds?: string[]
  allowedRoleIds?: string[]
  status?: DocumentStatus
  currentVersion?: number
  permissionLevel?: number
}

export interface DocumentChunk {
  chunkId: string
  documentId: string
  filename?: string
  content: string
  chunkIndex: number
  contentHash?: string
  createdAt?: string
  status?: string
  documentStatus?: string
  version?: number
  parentId?: string
  modality?: string
  imageRef?: string
}

export interface DocumentIngestTask {
  documentId?: string
  status?: string
  step?: string
  chunkCount?: number
  createdAt?: string
  updatedAt?: string
  errorMessage?: string
  [key: string]: unknown
}

export function listDocuments(): Promise<DocumentInfo[]> {
  return request.get('/documents') as unknown as Promise<DocumentInfo[]>
}

export function listChunks(documentId: string): Promise<DocumentChunk[]> {
  return request.get(`/documents/${documentId}/chunks`) as unknown as Promise<DocumentChunk[]>
}

export function getIngestStatus(documentId: string): Promise<DocumentIngestTask> {
  return request.get(`/documents/${documentId}/ingest-status`) as unknown as Promise<DocumentIngestTask>
}
