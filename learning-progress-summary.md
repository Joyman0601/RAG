# 大模型应用学习进度总结

用途：用于新窗口接续学习，避免上下文过长导致混乱。

## 当前定位

学习目标：

- 初级 Java 后端转向 Agent / 大模型应用开发。
- 以通过面试和完成可讲项目为主。
- 深度模型算法、训练、微调细节后置。

技术路线：

- 主线：Java 17 + Spring Boot 3。
- 辅助：Python 只作为阅读 AI 生态、跑 demo、写脚本的补充。
- 项目路径：`E:\yhl\RAG`

推荐面试定位：

> 我是 Java 后端出身，正在转向大模型应用开发，能用 Spring Boot 落地 LLM API、RAG、Tool Calling 和 Agent 工作流，也能阅读 Python AI 生态代码。

## 项目现状

项目路径：

```text
E:\yhl\RAG
```

当前技术栈：

```text
Java 17
Spring Boot 3.3.7
Maven
OpenAI-compatible chat/completions API
```

当前端口：

```text
19090
```

已经完成的接口：

```text
POST /api/chat
POST /api/chat/stream
POST /api/intent
```

已完成能力：

- 普通阻塞式 LLM 调用。
- 流式输出 SSE。
- 结构化 JSON 输出示例：意图识别。
- 配置化模型调用。
- API Key、base-url、model 配置。
- temperature 配置。
- timeout 配置。
- 基础异常处理。
- 全局异常返回。
- 日志记录。
- 输入长度限制。
- 输出长度限制。
- 基础成本控制。

## 第一周已完成内容

### 1. LLM API 基础

核心链路：

```text
用户请求
-> Controller 参数校验
-> Service 业务处理
-> LlmClient 调用模型
-> 解析模型响应
-> 返回结果
-> 日志和异常处理
```

核心认知：

> LLM 不是后端逻辑的替代品，而是一个可以生成文本或结构化内容的外部服务。

后端仍然负责：

```text
参数校验
业务流程
权限控制
异常处理
数据落库
接口稳定性
成本控制
安全边界
```

### 2. messages

模型输入通常包含：

```json
[
  {
    "role": "system",
    "content": "你是一个严谨的后端和大模型应用开发助手。"
  },
  {
    "role": "user",
    "content": "RAG 是什么？"
  }
]
```

面试表达：

> 我会把用户原始输入和系统约束拆开。用户输入放在 user message，行为边界、输出风格、安全要求放在 system message，这样比把所有内容拼成一个字符串更清晰，也更容易维护。

### 3. system prompt

核心认知：

> Prompt 只能提高模型遵循规则的概率，不能作为强约束。

面试表达：

> 生产系统里，权限、参数校验、工具调用边界和输出校验必须由后端实现，不能只依赖 prompt。

### 4. temperature

经验值：

```text
0 - 0.2：稳定、保守，适合分类、抽取、JSON、工具参数
0.3 - 0.6：适合知识问答、客服、普通解释
0.7 - 1.0：适合创意写作、文案、头脑风暴
```

面试表达：

> 对业务系统来说，我一般不会随便把 temperature 调高。像分类、信息抽取、工具调用参数生成这类任务，我会使用较低 temperature，以提高稳定性。

### 5. 异常处理

需要处理：

```text
api-key 为空
认证失败 401
限流 429
模型服务 5xx
网络超时
响应体为空
响应格式不符合预期
```

面试表达：

> 我会把 LLM API 当成外部不稳定服务处理，所以会设置超时、判断响应状态、校验响应结构，并做统一异常返回。

### 6. 日志

建议记录：

```text
requestId
model
inputChars
outputChars
durationMs
success/fail
errorType
```

谨慎记录：

```text
完整用户输入
完整模型输出
完整 prompt
完整 RAG 上下文
```

面试表达：

> LLM 日志要服务于排查问题，但不能无脑记录完整 prompt 和输出。涉及用户隐私、公司文档和业务数据时，需要脱敏、截断或按权限查看。

### 7. 结构化输出

当前项目已实现：

```text
POST /api/intent
```

示例请求：

```json
{
  "message": "我想查一下订单 123456 的状态"
}
```

示例响应：

```json
{
  "intent": "order_query",
  "confidence": 0.92
}
```

支持的 intent：

```text
chat
rag_query
order_query
```

核心认知：

> 自然语言输出适合展示给用户；结构化输出适合交给程序继续处理。

面试表达：

> 对结构化输出，我不会只依赖 prompt。我会结合后端 DTO、JSON 解析、字段校验和失败兜底来保证稳定性。

confidence 含义：

```text
confidence 是模型对本次分类结果的自评置信度，范围通常是 0.0 到 1.0。
它不是严格数学概率，只能作为业务路由参考。
```

### 8. 流式输出 SSE

当前项目已实现：

```text
POST /api/chat/stream
```

核心区别：

```text
普通输出：choices[0].message.content
流式输出：choices[0].delta.content
```

SSE 特点：

```text
服务端单向推送
基于 HTTP
适合 LLM 文本流式返回
比 WebSocket 更简单
```

面试表达：

> 普通 LLM 调用是等待模型完整生成后一次性返回，流式输出则是在模型生成 token 的过程中，后端通过 SSE 持续推送给前端。这样可以降低首字延迟，改善长回答场景的体验。

### 9. token、上下文窗口、成本控制

核心概念：

```text
输入 token = system prompt + user message + 历史对话 + RAG 上下文
输出 token = 模型生成的答案
总成本 = 输入成本 + 输出成本
```

当前项目已做基础控制：

```text
max-input-chars
max-output-tokens
```

面试表达：

> LLM 应用不能无限制把所有上下文都塞给模型。我会限制用户输入长度、历史对话轮数、RAG 召回数量和模型最大输出长度，用来控制成本、延迟和上下文质量。

## 第一周总结话术

可以在面试中这样说：

> 我用 Spring Boot 实现了一个 LLM 接入基础项目，支持普通聊天、流式输出和结构化意图识别。后端做了参数校验、配置化模型调用、超时、异常处理、日志和基础成本控制。我没有把模型当成稳定函数，而是按外部服务处理，关注响应解析、错误兜底和调用边界。

## 第二周目标：RAG

第二周主题：

```text
RAG：Retrieval-Augmented Generation，检索增强生成
```

第二周要完成的能力：

```text
文档上传
文档解析
文本切分 chunking
embedding
向量存储
向量检索
基于上下文回答
引用来源
无答案兜底
```

第二周核心问题：

```text
RAG 到底解决什么问题？
为什么不是直接把文档丢给模型？
RAG 和微调有什么区别？
chunk size 怎么选？
向量检索为什么会召回错误？
怎么减少幻觉？
怎么返回引用来源？
```

## 新窗口继续学习的提示词

复制下面这段到新窗口：

```text
我正在学习从 Java 后端转向 Agent / 大模型应用开发。

请你只作为老师给我上课，把握学习进度，不要直接改代码。具体代码实现和报错调试，我会在另一个窗口处理；你只需要在需要写代码时给我简洁明确的提示词。

我的当前进度：

第一周 LLM API 基础已经完成。

项目路径：
E:\yhl\RAG

技术栈：
Java 17 + Spring Boot 3.3.7 + Maven + OpenAI-compatible chat/completions API

已完成接口：
1. POST /api/chat
   普通阻塞式聊天接口

2. POST /api/chat/stream
   SSE 流式输出接口

3. POST /api/intent
   结构化输出示例，用于意图识别

已完成能力：
- 配置化 LLM 调用
- API Key / base-url / model 配置
- temperature 配置
- timeout 配置
- 统一异常处理
- 全局错误返回
- 日志记录
- 输入长度限制
- 输出 token 限制
- 基础成本控制

现在请从第二周开始继续带我学习：

第二周主题：RAG。

请先讲第一课：
RAG 到底解决什么问题？为什么不是直接把文档丢给模型？

要求：
1. 按面试导向讲。
2. 结合 Java 后端项目视角讲。
3. 每节课结束时给我一个可以复制到代码窗口的实现提示词。
4. 控制节奏，不要一次讲太多。
```

## 第二周第一课标题

```text
RAG 到底解决什么问题？为什么不是直接把文档丢给模型？
```

