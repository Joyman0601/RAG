# Contextual Retrieval：给子块加 LLM 上下文前缀再 embedding

> 一句话总结：借鉴 Anthropic 2024《Contextual Retrieval》，在**入库 embedding 之前**用 LLM 为每个子块生成一句「它在父块/全文中的定位」前缀，拼到待 embedding 文本前，让孤立小块也带上全局语境，提升召回；**展示与回填仍用原文**，开关 `rag.contextual.enabled` 默认关、零回归。父块/全文作为 `cache_control` 可缓存前缀复用现有 Prompt Caching 压成本。

## 0. 为什么需要它

分块（见 [chunking-strategy.md](chunking-strategy.md)）把文档切成小子块换检索精度，但小块有个老问题：**脱离上下文后语义残缺**。

举两个真实会翻车的例子：

- 子块正文是「需要 JDK 17 及以上。」——它属于「安装 / 环境要求」还是「升级 / 兼容性」？向量里看不出来，query「安装前要准备什么」可能召不回它。
- 子块正文是「点击右上角『申请』按钮提交。」——申请什么？请假、报销还是离职？指代信息在父块标题里，子块自身丢了。

Anthropic 的做法：embedding 之前，让 LLM 看着**整篇文档（或其父级章节）**，为这个子块补一句定位说明（如「本片段说明请假流程中的提交步骤」），把它**前置**到子块文本再做 embedding。向量里于是同时编码了「局部内容 + 全局定位」，召回率显著提升。

关键约束：**只改 embedding 的输入，不改展示**。检索命中后回填给 LLM、返回给前端的 source，仍是干净的原文——前缀只为向量服务，不污染内容。

## 1. 架构：ContextualEnricher

新建 `com.yhl.rag.document.ContextualEnricher`，单一职责：

```java
// 返回待 embedding 文本：成功时为「定位前缀 + \n + 原文」，否则原文不变。
String buildEmbeddingText(String content, String contextSource);
```

- `content`：子块原文（展示/回填用的也是它，永不被改写）。
- `contextSource`：父块正文或文档全文，作为 LLM 理解定位的背景。

调用 LLM 的方式刻意贴合 [prompt-caching](rag-enhancement-devlog.md) 的既有机制：

```
system  (instructions)  ← 固定任务说明 + contextSource    ← cache_control:{ephemeral} 注入在这一块
user    (input)         ← 该子块正文
output                  ← ≤50 字定位说明
```

把 `contextSource` 放进 **system instructions**，是因为 `LlmClient` 在 `cache-enabled` 时正是对 system 块注入 `cache_control`。于是**同一父块/全文的多个子块，system 内容完全一致 → 命中缓存**：第 1 个子块付全价缓存写入，后续子块的父块/全文部分按缓存价计费。一篇文档切 N 个子块，原本要把全文重读 N 遍，现在只读 1 遍。

## 2. 入库链路接入

`DocumentService.embedChunks` 原来是 `embed(chunk.getContent())`，现在：

```
contextSource = 子块所属父块正文（有 parentId 且父块存在）  否则  文档全文
embeddingText = contextualEnricher.buildEmbeddingText(子块原文, contextSource)
vector        = embeddingClient.embed(embeddingText)
```

- **父块优先、全文兜底**：MARKDOWN 策略下子块带 `parentId`，上下文源取那一段父块（更聚焦、缓存粒度更细）；FIXED/SEMANTIC 无父块时退回全文。
- **原文不变**：`embeddingText` 是临时变量，不落库；`chunk.content` 保持原文，回填/展示零影响。无需给 `DocumentChunk` 加字段。
- **接入点唯一**：`update` 与 `processIngestTask` 两条入库路径都经过 `embedChunks`，一处接入两处生效。公开 API `chunkText` 不走 embedding，不受影响。

## 3. 零回归与降级

默认 `enabled=false`，`buildEmbeddingText` 直接返回原文，整条链路逐字节等同改造前。开启后任一不利条件都**降级为不加前缀、不阻断入库**：

| 条件 | 行为 |
| --- | --- |
| 开关关 | 返回原文，不调 LLM |
| 无 LlmClient（测试构造器） | 返回原文 |
| 子块或上下文源为空 | 返回原文，不调 LLM |
| LLM 抛 `LlmException`（超时/限流/HTTP 错） | catch 后返回原文，`WARN contextual_enrich_fallback` |
| LLM 返回空串 | 返回原文，`WARN contextual_enrich_empty` |

降级理念与 `QueryRewriterService` 一致：增强类功能失败时回退到「未增强」的正确行为，绝不让一次 LLM 抖动卡死入库。

`contextSource` 超 8000 字截断，避免父块/全文撑爆模型输入；相同截断结果稳定，不影响缓存命中。

## 4. 测试（先写后实现，TDD）

- `ContextualEnricherTest`（6 例，假 `LlmClient`）：
  - 开关关 → 原文，且 `verifyNoInteractions(llmClient)`；
  - 开启 + LLM 返回前缀 → 输出 `前缀\n原文`，且断言 **contextSource 进了 system instructions**（缓存前缀注入）、**子块进了 user 消息**；
  - LLM 抛异常 / 返回空白 / contextSource 空白 / 无 LlmClient → 均降级为原文。
- `DocumentContextualEmbeddingTest`（2 例，真 `DocumentService` + 假 client）：
  - 开启 → `ArgumentCaptor` 捕获 `embeddingClient.embed` 入参带前缀，且 `chunk.content` 不含前缀（展示用原文）；
  - 关闭 → embed 入参为纯原文（零回归）。

全量 **135 通过 / 0 失败 / 2 跳过**（原 127，本轮 +8）。

## 5. 量化（Hit@K / recall，待联网重跑）

方法学与 #3 一致，**不造数**：本机无 embedding/LLM 端点与网络，下面是复现配方，留待有 API 环境时重跑填表。

复现（同一评估集、同一检索模式，仅切换 contextual 开关跑两遍对比）：

```bash
# baseline：contextual 关
MEASUREMENT_RUN=true RAG_CONTEXTUAL_ENABLED=false \
  LLM_API_KEY=... LLM_EMBEDDING_BASE_URL=... LLM_EMBEDDING_API_KEY=... \
  mvn -o test -Dtest=RagMeasurementHarnessTest

# treatment：contextual 开（其余不变）
MEASUREMENT_RUN=true RAG_CONTEXTUAL_ENABLED=true \
  LLM_API_KEY=... LLM_EMBEDDING_BASE_URL=... LLM_EMBEDDING_API_KEY=... \
  mvn -o test -Dtest=RagMeasurementHarnessTest
```

harness 内部已对 VECTOR / HYBRID / HYBRID_RERANK 三模式各出 Hit@K / Recall@K / MRR，两遍结果对照即得 contextual 的增益。建议在 MARKDOWN+父块 (`RAG_CHUNK_STRATEGY=MARKDOWN`、`RAG_CHUNK_PARENT_DOCUMENT_ENABLED=true`) 与缓存开启 (`LLM_API_STYLE=chat`、`LLM_CACHE_ENABLED=true`) 下跑，才能同时量到「召回增益」与「缓存省下的 token」。

| 配置 | Hit@3 | Recall@3 | MRR | 缓存命中率 | 备注 |
| --- | --- | --- | --- | --- | --- |
| contextual=off（baseline） | 待重跑 | 待重跑 | 待重跑 | — | |
| contextual=on | 待重跑 | 待重跑 | 待重跑 | 待重跑 | 期望 Hit/Recall ↑ |

> 与 `docs/eval-metrics.md` 的 `FIXED / MARKDOWN+parent / +contextual` 三方案表对齐——这一栏即「+contextual」。

## 6. 设计取舍

- **为什么不把前缀也存进 content？** 前缀是为向量服务的「检索辅助」，不是事实正文。存进 content 会污染回填给 LLM 的上下文（让模型看到一句机器生成的元描述），还会让 source 展示变脏。只改 embedding 输入是最小侵入。
- **为什么上下文源进 system 而不是 user？** 缓存只对 system 块生效，且把大段不变的父块/全文放 system、把每次变化的子块放 user，是 Prompt Caching 的标准用法——最大化跨子块的前缀复用。
- **为什么默认关？** 它给每个子块加一次 LLM 调用，入库变慢、有成本；缓存能压成本但不能压到零。默认关保证零回归，需要召回增益时再开。
