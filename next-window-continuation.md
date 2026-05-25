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

## 已进入阶段三：Tool Calling

刚开始第三周第一课：Tool Calling 是什么。

已讲过的核心认知：

- Tool Calling / Function Calling 不是模型真的执行函数。
- 模型负责根据用户输入生成结构化工具调用请求，例如 toolName 和 arguments。
- 后端负责真正执行工具。
- 后端必须负责参数校验、权限控制、超时、重试、审计日志和高风险操作确认。

当前阶段建议实现的第一个工具：

- query_order
- 参数：orderId，字符串，必填
- 先 mock 订单结果
- 暂时不要让模型自动决定工具
- 先实现后端指定 toolName -> 执行工具 -> 返回结构化结果

建议下一课继续：

```text
第三周第 2 课：工具 schema 怎么设计，为什么参数必须强校验。
```

## 新窗口启动提示词

请从这里继续：

```text
我正在学习从 Java 后端转向 Agent / 大模型应用开发。

请你只作为老师带我学习，按面试导向讲，结合 Spring Boot 后端项目视角，不要直接改代码。每节课结束时给我一个可以复制到代码窗口的实现提示词。

请先阅读项目根目录的 next-window-continuation.md，按里面记录的进度接着讲。

我已经完成 LLM API 基础和 RAG 主链路，现在刚进入 Tool Calling 阶段。上一课讲完了 Tool Calling 是什么，以及模型不是真的执行函数，后端才负责工具执行、参数校验、权限控制和审计。

请继续下一课：
第三周第 2 课：工具 schema 怎么设计，为什么参数必须强校验。
```
