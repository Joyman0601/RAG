# 面试强化阶段新窗口接续提示词

用途：当前学习已经完成 LLM API、RAG、Tool Calling / Agent、生产化工程增强四个阶段。下一阶段进入“方向 A：面试强化”，用于在新窗口继续训练项目表达和追问回答。

## 当前学习定位

我正在从 Java 后端转向 Agent / 大模型应用开发。

目标不是做模型算法工程师，而是做能用 Spring Boot 落地 LLM API、RAG、Tool Calling、Agent 工作流和生产化治理的大模型应用后端开发。

请你只作为老师带我学习，按面试导向讲解，结合 Spring Boot 后端项目视角，不要直接改代码。每节课结束时给我一个可以复制到代码窗口的实现提示词，或者给我一段可以背诵/练习的面试表达。

## 技术路线

- Java 17
- Spring Boot 3.3.7
- Maven
- OpenAI-compatible API
- 项目路径：E:\yhl\RAG

## 已完成阶段

### 阶段一：LLM API 基础

已完成：

- 普通阻塞式 LLM 调用
- SSE 流式输出
- 结构化 JSON 输出
- 配置化模型调用
- API key / base-url / model / temperature / timeout 配置
- 异常处理和全局异常返回
- 日志记录
- 输入长度限制
- 输出 token 限制
- 基础成本控制

核心认知：

- LLM 是外部不稳定服务，不是后端逻辑替代品。
- Prompt 不是强约束，权限、参数校验、输出校验必须由后端实现。
- 结构化输出不能只靠 prompt，要结合 DTO、JSON 解析、字段校验和失败兜底。

### 阶段二：RAG 主链路

已完成：

- 文档上传
- 文本解析
- chunk 切分
- chunk metadata
- embedding 客户端
- 内存版向量保存和检索
- topK 和 score threshold
- RAG search / ask
- context prompt 构造
- answer + sources 返回
- 无答案兜底
- debug retrievedChunks
- RAG 调试日志
- RAG 评估接口
- 成本和耗时统计
- contentHash
- 文档删除和更新
- DocumentInfo / DocumentChunk status 和 version
- 逻辑废弃旧 chunk + 写入新版本
- 移除旧 embedding，避免旧内容继续被检索
- 基础权限 metadata 和权限过滤设计

RAG 核心认知：

- sources 必须由后端根据实际进入 context 的 chunk 生成，不能由模型编造。
- 文档更新要通过 version/status 管理，检索只查 ACTIVE 且当前版本的 chunk。
- 权限过滤要下推到向量检索阶段，否则会先产生召回偏差，再导致有效召回不足。
- context 二次校验发生在向量检索之后、大模型调用之前，用数据库最新权限防止脏 metadata 泄露。

### 阶段三：Tool Calling / Agent

已完成：

- 工具 schema 设计
- 参数强校验
- ToolRegistry
- ToolExecutor
- ToolExecutionService
- 模型选择工具，后端裁决
- 工具结果封装和脱敏
- 权限控制
- 高风险工具二次确认
- Agent Loop
- ConversationState
- RAG 工具化 search_knowledge_base
- 状态机工作流
- Agent 测试策略
- Agent 可观测性
- Agent 安全清单
- Agent 阶段面试讲法

Agent 核心认知：

- Tool Calling 不是模型执行函数，而是模型生成候选工具调用，后端裁决并执行。
- 模型传来的 arguments 是不可信输入，要转 DTO 并做 Bean Validation。
- userId、tenantId、role 等权限字段不能让模型传，必须来自后端认证上下文。
- 高风险工具不能自动执行，必须进入后端确认流程。
- Agent Loop 必须限制最大步数、超时、工具白名单和重复调用。
- Agent 的智能靠模型，Agent 的可靠性靠后端治理。

### 阶段四：生产化与工程增强

已完成：

- 从内存向量库迁移到真实向量库的选型：pgvector / Milvus / Elasticsearch
- 文档解析和异步入库任务
- embedding 任务状态、失败重试和幂等设计
- RAG 评估集：Hit@K、Recall@K、MRR、回答质量
- 多租户和企业权限模型
- 缓存策略：embedding cache、RAG search cache、谨慎缓存 answer
- 成本治理：token 预算、限流、配额、模型分级
- 生产日志、指标和告警
- Agent 工具灰度发布和 Shadow Mode
- 最终项目复盘和面试模拟

生产化核心认知：

- 当前项目第一步更适合 pgvector；大规模向量检索考虑 Milvus；关键词 + 向量混合检索考虑 Elasticsearch。
- 上传成功不等于文档可检索，parse/chunk/embedding/vector indexing 应异步执行。
- 异步任务要有状态、重试、稳定 chunkId、upsert 和 documentVersion，保证幂等。
- RAG 评估要拆成检索质量和回答质量。
- 权限要贯穿入库 metadata、向量检索过滤、context 二次校验、sources 白名单。
- 缓存 key 必须包含权限、模型、prompt、检索参数、知识库版本。
- 成本治理要覆盖请求前、执行中、调用后。
- 监控按 LLM、RAG、Agent 三层看；每层看量、慢、错、贵；RAG 额外看准不准，Agent 额外看控不控。
- Agent 工具上线先离线评估，再 Shadow Mode，再白名单，再灰度，最后全量。

## 当前阶段：方向 A，面试强化

下一阶段目标：

把已经学过的知识压缩成面试时能自然说出来的表达，而不是继续堆新概念。

建议课程路线：

```text
面试强化第 1 课：2 分钟项目介绍怎么讲
面试强化第 2 课：简历项目描述怎么写，如何突出 Java 后端转大模型应用
面试强化第 3 课：RAG 高频追问专项，包括检索、chunk、sources、权限、评估
面试强化第 4 课：Agent 高频追问专项，包括 Tool Calling、后端裁决、安全、确认流程
面试强化第 5 课：生产化追问专项，包括异步入库、幂等、缓存、成本、监控、灰度
面试强化第 6 课：项目深挖模拟，面试官连续追问
面试强化第 7 课：把项目和 Java 八股结合，比如线程池、事务、锁、MQ、Redis、数据库
面试强化第 8 课：根据项目生成简历 bullet 和 STAR 表达
面试强化第 9 课：模拟一场完整技术面
面试强化第 10 课：最终背诵稿和临场答题模板
```

## 下一课请从这里开始

```text
面试强化第 1 课：2 分钟项目介绍怎么讲
```

## 新窗口启动提示词

请从这里继续：

```text
我正在学习从 Java 后端转向 Agent / 大模型应用开发。

请你只作为老师带我学习，按面试导向讲，结合 Spring Boot 后端项目视角，不要直接改代码。每节课结束时给我一个可以复制到代码窗口的实现提示词，或者一段可以背诵/练习的面试表达。

请先阅读项目根目录的 interview-next-window-continuation.md，按里面记录的进度接着讲。

我已经完成四个阶段：
1. LLM API 基础：普通调用、SSE 流式输出、结构化 JSON、异常处理、token 和成本控制。
2. RAG 主链路：文档上传、chunk、embedding、向量检索、sources、文档更新、旧 embedding 移除、权限过滤。
3. Tool Calling / Agent：工具 schema、参数校验、ToolRegistry、ToolExecutionService、后端裁决、高风险确认、Agent Loop、RAG 工具化、状态机、测试、安全和面试讲法。
4. 生产化工程增强：真实向量库选型、异步入库、重试幂等、RAG 评估、多租户权限、缓存、成本治理、监控告警、灰度发布和 Shadow Mode。

现在进入方向 A：面试强化。

请继续下一课：
面试强化第 1 课：2 分钟项目介绍怎么讲。
```
