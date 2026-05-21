# RAG Learning

Java Spring Boot project for learning LLM, RAG, and Agent application development.

## Current Stage

Week 1: LLM API basics and a minimal in-memory RAG flow.

Current endpoints:

- `POST /api/chat`: chat with short in-memory conversation history.
- `POST /api/chat/stream`: stream chat response as Server-Sent Events.
- `POST /api/intent`: classify a message into `chat`, `rag_query`, or `order_query`.
- `POST /api/documents/upload`: upload a txt or markdown document for the first RAG ingestion stage.
- `GET /api/documents`: list uploaded document metadata.
- `GET /api/documents/{documentId}/chunks`: list chunks generated from an uploaded document.
- `POST /api/rag/documents`: add a document to the in-memory knowledge base.
- `GET /api/rag/documents`: list current in-memory documents.
- `POST /api/rag/query`: retrieve relevant documents and answer from their context.
- `POST /api/rag/search`: embed a question and return matching in-memory chunks.
- `POST /api/rag/ask`: retrieve matching chunks, call the LLM with those chunks as context, and return an answer with sources.

## 为什么选择 RAG 而不是微调

在企业知识库问答场景中，RAG 通常比微调更适合作为第一阶段方案。原因是企业文档、制度、FAQ、产品说明会持续变化，如果采用 RAG，文档更新后只需要重新进入知识库或向量库，检索阶段即可使用最新资料；如果依赖微调，则每次知识变化都可能涉及重新准备数据、训练、评估和发布模型，维护成本更高。

RAG 还有一个重要优势是可解释性。系统可以把命中的 chunk 作为引用来源返回，方便用户和开发者判断答案依据，也便于排查回答错误的问题。后续接入账号、部门、角色等信息后，检索前还可以结合权限过滤，避免用户看到无权限文档内容。

微调并不是被否定，它更适合解决输出风格、固定格式、稳定任务模式等问题，例如让模型长期遵循某种话术或结构化输出习惯。本项目当前目标是实现企业资料问答链路，因此优先实现 RAG：文档上传、切分、embedding、检索、基于上下文回答；当前阶段不涉及模型训练。

## Configuration

The relay used by this project expects GPT text calls on `POST {base-url}/v1/responses`.

```bash
LLM_API_KEY=your-api-key
LLM_BASE_URL=https://co.yes.vg
LLM_MODEL=gpt-5.5
LLM_TEMPERATURE=0.7
LLM_TIMEOUT_SECONDS=30
LLM_MAX_OUTPUT_TOKENS=800
LLM_MAX_INPUT_CHARS=2000
LLM_EMBEDDING_BASE_URL=https://your-embedding-provider
LLM_EMBEDDING_API_KEY=your-embedding-api-key
LLM_EMBEDDING_MODEL=text-embedding-3-small
LLM_EMBEDDING_TIMEOUT=30
```

`LLM_EMBEDDING_BASE_URL` must point to a provider that supports `POST /v1/embeddings`. The text relay `LLM_BASE_URL` may not support embeddings.

Optional proxy:

```bash
LLM_PROXY_HOST=127.0.0.1
LLM_PROXY_PORT=7890
```

RAG chunking:

```bash
RAG_CHUNK_SIZE=600
RAG_CHUNK_OVERLAP=100
RAG_SEARCH_TOP_K=3
RAG_SEARCH_SCORE_THRESHOLD=0.3
RAG_CONTEXT_MAX_CHARS=3000
RAG_DEBUG_ENABLED=false
```

## Chat Example

```http
POST /api/chat
Content-Type: application/json

{
  "conversationId": "optional-existing-id",
  "message": "RAG 是什么？"
}
```

Response:

```json
{
  "answer": "...",
  "conversationId": "..."
}
```

## Stream Chat Example

The stream endpoint also uses the relay-compatible Responses API path internally: `POST {base-url}/v1/responses`.

```bash
curl -N -X POST http://localhost:19090/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d "{\"message\":\"RAG 是什么？\"}"
```

## Intent Example

```http
POST /api/intent
Content-Type: application/json

{
  "message": "我想查一下订单 123456 的状态"
}
```

## Cost Control

Current stage uses character length as an approximate token/cost guard:

- `llm.max-input-chars`: maximum user input length. Default: `2000`.
- `llm.max-output-tokens`: maximum model output budget. Default: `800`.

This relay uses the Responses API, so requests send `max_output_tokens`. For chat/completions providers, the equivalent field is usually `max_tokens`.

Response:

```json
{
  "intent": "order_query",
  "confidence": 0.92
}
```

## RAG Example

Upload a source document:

```bash
curl -X POST http://localhost:19090/api/documents/upload \
  -F "file=@README.md"
```

Response:

```json
{
  "id": "...",
  "filename": "README.md",
  "contentType": "application/octet-stream",
  "size": 1234,
  "createdAt": "..."
}
```

List uploaded documents:

```bash
curl http://localhost:19090/api/documents
```

List document chunks:

```bash
curl http://localhost:19090/api/documents/{documentId}/chunks
```

Uploaded text is split into fixed-size chunks using `rag.chunk-size` with `rag.chunk-overlap` retained between adjacent chunks. After chunking, each chunk is sent to the embedding API and the returned vector is stored in memory by `chunkId`.

Search similar chunks:

```bash
curl -X POST http://localhost:19090/api/rag/search \
  -H "Content-Type: application/json" \
  -d "{\"question\":\"病假需要什么材料？\"}"
```

The search endpoint embeds the question, compares it with in-memory chunk vectors by cosine similarity, returns top matching chunks, and does not call an LLM.

### 如何排查 RAG 召回不准

当 RAG 回答不准时，先不要直接调整 prompt，应先检查 `/api/rag/search` 的召回结果。重点看正确 chunk 是否出现在返回列表中，以及它的 `score` 是否明显低于其他 chunk。如果正确 chunk 没有被召回，通常需要检查文档是否上传成功、chunk 切分是否把关键语义截断，或者适当增大 `RAG_SEARCH_TOP_K`。

如果正确 chunk 被召回但没有进入问答上下文，可以观察 score 分布，并降低 `RAG_SEARCH_SCORE_THRESHOLD`。调试时可以调用 `/api/rag/search?includeBelowThreshold=true`，让接口返回 top_k 候选中低于 threshold 的结果，并通过 `included=false` 判断哪些结果被过滤。

如果向量分数整体偏低或相近，说明仅靠向量检索可能不稳定。后续可以加入关键词检索做混合召回，或在向量召回后增加 rerank 模型重新排序。当前项目仍保持内存版向量检索，不急于接真实向量数据库。

Ask with retrieved context:

```http
POST /api/rag/ask
Content-Type: application/json

{
  "question": "病假需要什么材料？"
}
```

RAG ask debug mode is disabled by default. To return retrieved chunk details while debugging, start the app with:

```bash
RAG_DEBUG_ENABLED=true
```

Then call:

```http
POST /api/rag/ask?debug=true
Content-Type: application/json

{
  "question": "病假需要什么材料？"
}
```

When both the config and query parameter are enabled, the response includes `retrievedChunks` with each chunk score, whether it entered the final context, and a `contentPreview` capped at 100 characters. Normal logs still avoid printing full questions, chunks, prompts, answers, or vectors.

Add a document:

```http
POST /api/rag/documents
Content-Type: application/json

{
  "title": "退货规则",
  "content": "商品签收后 7 天内支持无理由退货，定制商品除外。"
}
```

Ask from the knowledge base:

```http
POST /api/rag/query
Content-Type: application/json

{
  "question": "签收后多久可以退货？"
}
```

Response:

```json
{
  "answer": "...",
  "sources": [
    {
      "id": "...",
      "title": "退货规则",
      "score": 6,
      "snippet": "商品签收后 7 天内支持无理由退货，定制商品除外。"
    }
  ]
}
```

## Run

```bash
mvn spring-boot:run
```

## Test

```bash
curl -X POST http://localhost:19090/api/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"RAG 是什么？\"}"
```
