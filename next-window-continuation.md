# 新窗口接续提示词

用途：当前上下文较长，用于新窗口继续学习，避免重复讲已完成内容。

## 当前学习定位

我正在从初级 Java 后端转向 Agent / 大模型应用开发。

目标不是做模型算法工程师，而是做能用 Spring Boot 落地 LLM API、RAG、Tool Calling 和 Agent 工作流的大模型应用后端开发。

请你只作为老师带我学习，控制节奏，按面试导向讲解。不要直接改代码。需要实现时，请给我一段可以复制到代码窗口的实现提示词。

## 技术路线

- Java 17
- Spring Boot 3.3.7
- Maven
- OpenAI-compatible chat/completions API
- 项目路径：E:\yhl\RAG

## 已完成阶段一：LLM API 基础

已完成接口：

- POST /api/chat
- POST /api/chat/stream
- POST /api/intent

已完成能力：

- 普通阻塞式 LLM 调用
- SSE 流式输出
- 结构化 JSON 输出示例：意图识别
- 配置化模型调用
- API key / base-url / model / temperature / timeout 配置
- 基础异常处理和全局异常返回
- 日志记录
- 输入长度限制
- 输出 token 限制
- 基础成本控制

核心认知：

- LLM 是外部不稳定服务，不是后端逻辑替代品。
- Prompt 不是强约束，权限、参数校验、输出校验必须由后端实现。
- 结构化输出不能只靠 prompt，要结合 DTO、JSON 解析、字段校验和失败兜底。

## 已完成阶段二：RAG 主链路

已学习并基本实现：

- 文档上传
- 文本解析
- chunk 切分
- chunk size / overlap 配置
- chunk metadata
- embedding 客户端
- 内存版向量保存
- 内存版向量检索
- top_k 和 score threshold
- /api/rag/search
- /api/rag/ask
- context prompt 构造
- answer + sources 返回
- sources 由后端根据进入 context 的 chunk 生成
- 无答案兜底
- debug 返回 retrievedChunks
- RAG 调试日志
- RAG 评估接口
- 成本和耗时统计
- contentHash
- 文档删除
- 文档更新
- DocumentInfo / DocumentChunk status 和 version
- 逻辑废弃旧 chunk + 写入新版本
- 移除旧 chunk 对应 embedding，避免旧内容继续被检索
- 基础权限 metadata 和权限过滤设计

当前 RAG 更新策略：

```text
1. 找到原文档 DocumentInfo
2. 读取旧 chunks
3. 把旧 chunks 标记为 DELETED
4. 从内存 embedding Map 移除旧 chunks 对应向量
5. 文档 version + 1
6. 用新文件重新解析文本
7. 重新切 chunk
8. 新 chunks 写入 ACTIVE + 新 version
9. 重新调用 embedding 并保存新向量
```

这个策略是“逻辑废弃旧版本 + 写入新版本”，不是物理删除所有历史记录。检索时应只使用 ACTIVE 且匹配当前版本的 chunk。

RAG 核心面试表达：

> 我做了一个基于 Spring Boot 的企业知识库 RAG 问答系统。它支持文档上传、自动切分、embedding 向量化、语义检索、基于上下文回答，并返回引用来源。相比直接让模型回答，这个系统能让答案基于企业内部资料，同时支持无答案兜底、文档更新、调试评估、成本统计和基础权限过滤。

## 已完成阶段三：Tool Calling / Agent

已学习的课程：

- Tool Calling 是什么：模型不执行函数，只生成候选 tool call，后端执行。
- 工具 schema 怎么设计，为什么参数必须强校验。
- ToolRegistry / ToolExecutor / ToolExecutionService 的工具注册和统一执行链路。
- 模型如何选择工具，后端如何裁决。
- 工具执行结果如何返回给模型，为什么不能直接暴露 Entity。
- 权限控制与高风险工具二次确认。
- 单轮 Tool Calling 到受控 Agent Loop。
- Agent 上下文管理、短期记忆和长期记忆。
- Agent 可观测性、AgentStep、requestId、审计日志和错误码。
- 把 RAG 封装成 Agent 工具 search_knowledge_base。
- Agent 工作流与状态机，核心业务不能完全交给自由 Agent。
- Tool Calling / Agent 测试策略。
- Agent 上线前安全清单。
- Agent 项目的面试讲法与项目包装。
- Agent 阶段总结与下一阶段路线。

阶段三核心认知：

- Tool Calling 不是模型执行函数，而是模型生成候选工具调用，后端裁决并执行。
- Schema 是后端提供给模型的工具说明，arguments 是模型根据用户自然语言抽取出来的参数。
- 模型传来的 arguments 仍然是不可信输入，要转 DTO 并做 Bean Validation。
- userId、tenantId、role 这类权限字段不能让模型传，必须来自后端认证上下文。
- RAG 的 sources 必须由后端根据实际进入 context 的 chunk 生成，不能由模型编造。
- 高风险工具不能自动执行，必须进入后端确认流程。
- Agent Loop 必须限制最大步数、超时、工具白名单和重复调用，不能让模型无限循环。
- Agent 的记忆本质上是后端管理上下文，不是模型真的永久记住。
- Agent 的智能靠模型，Agent 的可靠性靠后端治理。

Agent 完整主链路：

```text
1. 用户输入自然语言
2. 后端根据当前用户权限，从 ToolRegistry 取可用工具 schema
3. 后端构造 messages，包括 system prompt、用户消息、必要 ConversationState
4. 调用 LLM
5. 模型返回普通文本或 tool call
6. 如果是普通文本，后端直接返回
7. 如果是 tool call，后端检查工具是否注册、是否授权、是否风险可执行
8. 后端将 arguments 转成强类型 DTO，并用 Bean Validation 校验
9. 后端做数据权限和业务规则校验
10. 低风险工具执行，高风险工具进入确认流程
11. 工具结果封装成 ToolResult，字段白名单和脱敏
12. 工具结果追加到上下文，再次调用 LLM 生成最终回答
13. 全链路记录 requestId、AgentStep、耗时、错误码和审计日志
```

第三阶段项目面试表达：

> 我做的是一个 Spring Boot 企业知识库 RAG + Agent 助手系统。底层封装了 OpenAI-compatible Chat API，支持普通问答、SSE 流式输出和结构化 JSON 输出；RAG 部分实现了文档上传、chunk 切分、embedding、向量检索、score threshold、sources 返回和文档更新；Agent 部分我设计了 ToolExecutor、ToolRegistry 和 ToolExecutionService，把订单查询、知识库检索等后端能力封装成工具，让模型可以根据用户自然语言生成 tool call。后端不会直接信任模型参数，而是做 DTO 校验、权限校验、业务校验和高风险确认，并通过 requestId、AgentStep 和审计日志保证可排查、可追踪。

## 下一阶段：第四周，生产化与工程增强

第四周重点不是继续堆 Agent 概念，而是把当前 demo 形态的 RAG + Agent 项目推向更像真实企业项目的工程形态。

建议课程路线：

```text
第四周第 1 课：从内存向量库迁移到真实向量库，Milvus / pgvector / Elasticsearch 怎么选
第四周第 2 课：文档解析和异步入库任务，为什么不能在上传接口里同步 embedding 大文件
第四周第 3 课：embedding 任务状态、失败重试和幂等设计
第四周第 4 课：RAG 评估集怎么做，如何判断检索质量和回答质量
第四周第 5 课：多租户和企业权限模型，文档级 / chunk 级权限怎么落地
第四周第 6 课：缓存策略，哪些 LLM/RAG/Tool 结果可以缓存，哪些不能缓存
第四周第 7 课：成本治理，token 预算、限流、配额、模型分级
第四周第 8 课：生产日志、指标和告警，怎么监控 LLM 应用
第四周第 9 课：灰度发布和 shadow mode，如何安全上线 Agent 工具
第四周第 10 课：最终项目复盘和面试模拟
```

下一课请从这里开始：

```text
第四周第 1 课：从内存向量库迁移到真实向量库，Milvus / pgvector / Elasticsearch 怎么选
```

## 新窗口启动提示词

请从这里继续：

```text
我正在学习从 Java 后端转向 Agent / 大模型应用开发。

请你只作为老师带我学习，按面试导向讲，结合 Spring Boot 后端项目视角，不要直接改代码。每节课结束时给我一个可以复制到代码窗口的实现提示词。

请先阅读项目根目录的 next-window-continuation.md，按里面记录的进度接着讲。

我已经完成：
1. LLM API 基础，包括普通调用、SSE 流式输出、结构化 JSON 输出、异常处理、token 和成本控制。
2. RAG 主链路，包括文档上传、chunk、embedding、向量检索、sources、文档更新、旧 embedding 移除和基础权限过滤。
3. Tool Calling / Agent 阶段，包括工具 schema、参数强校验、ToolRegistry、ToolExecutionService、模型选择工具、后端裁决、工具结果封装、高风险确认、Agent Loop、ConversationState、RAG 工具化、状态机工作流、测试策略、可观测性、安全清单和面试讲法。

现在进入下一阶段：
第四周：生产化与工程增强。

请继续下一课：
第四周第 1 课：从内存向量库迁移到真实向量库，Milvus / pgvector / Elasticsearch 怎么选。
```
