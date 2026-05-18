# RAG Learning

Java Spring Boot project for learning LLM, RAG, and Agent application development.

## Current Stage

Week 1: LLM API basics and a minimal in-memory RAG flow.

Current endpoints:

- `POST /api/chat`: chat with short in-memory conversation history.
- `POST /api/chat/stream`: stream chat response as Server-Sent Events.
- `POST /api/intent`: classify a message into `chat`, `rag_query`, or `order_query`.
- `POST /api/rag/documents`: add a document to the in-memory knowledge base.
- `GET /api/rag/documents`: list current in-memory documents.
- `POST /api/rag/query`: retrieve relevant documents and answer from their context.

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
```

Optional proxy:

```bash
LLM_PROXY_HOST=127.0.0.1
LLM_PROXY_PORT=7890
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
