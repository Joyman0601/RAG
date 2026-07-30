# RAG 能力增强开发日志（供博客生成）

> 本文件实时记录本轮 RAG 增强的每一步：动机、方案、关键决策、踩坑、量化结果。
> 用途：后续生成技术博客发布到 chandlerblog.com。沿用 docs/ 现有 per-feature md 流派。
> 每个功能落地后会另建独立 `docs/<feature>.md`，本文件作为索引 + 进度 + 跨功能决策记录。

## 本轮目标（2026-06 起）

针对「面大模型应用岗」补强 RAG 项目的技术深度与真实性，确定做以下 6 个代码功能 + 简历排版：

| 编号 | 功能 | 轨道 | 状态 | 独立文档 |
| --- | --- | --- | --- | --- |
| #9-11 | 简历排版精简（RAG bullet 10→7、拆 pgvector 巨段、按影响力重排） | C | ✅ 完成 | — |
| #3 | 分块策略升级：独立父块 Parent-Document + Markdown 结构感知 + 语义分块 | A | ✅ 完成 | [chunking-strategy.md](chunking-strategy.md) |
| #4 | Contextual Retrieval（Anthropic 2024：chunk 加 LLM 上下文前缀再 embedding） | A | ✅ 完成 | [contextual-retrieval.md](contextual-retrieval.md) |
| #1a | 多模态 RAG：真 VL embedding 端点，图片/PDF 与文本进同一向量空间 | A | ✅ 完成 | [multimodal-rag.md](multimodal-rag.md) |
| #7 | 多轮会话 RAG：结合历史的 conversational query rewrite + 历史压缩 | A | ✅ 完成 | [conversational-rag.md](conversational-rag.md) |
| #6 | 扩展评估集 26→50-100 题 + 标准答案 | B | ✅ 完成 | 内联于 `questions.json` + `EvalSetSchemaTest` |
| #5 | 评估维度补全：answer relevancy / context recall / answer correctness | B | ✅ 完成（代码就绪，reference-based 指标待判官联网重跑） | `docs/eval-metrics.md` |

## 跨窗口执行计划（方案3 · 三轨混合）

> 多个 Claude 窗口同时改**同一工作树**会互相覆盖文件。故按"改动文件不重叠"划轨道：
> - **轨道 A（串行，主工作树 `E:\yhl\RAG`）**：#3 → #4 → #1a → #7。都重度改 `DocumentService` / `RagProperties` / 检索-问答层，必须一次只跑一个窗口，做完一个再开下一个新窗口。
> - **轨道 B（并行，独立 worktree）**：#6 → #5。主要改 `eval/`、`questions.json`、`RagEvalService`，与轨道 A 热点文件基本不重叠。
>   开法：`git worktree add ../RAG-eval -b feat/rag-eval`，新窗口打开 `E:\yhl\RAG-eval`，完成后合回。
> - **轨道 C（并行，独立 repo `E:\yhl\tmp\resume\resume`）**：每个功能落地并量化后回填简历 bullet（保持 6-7 条纪律）。与代码永不冲突。
>
> **提示词**：见 `docs/rag-enhancement-prompts.md`，每个功能一段，新窗口粘贴即可自包含开工。
> **唯一事实源**：所有窗口动手前先读本文件「锁定的设计决策」+ 对应功能规格。

## 锁定的设计决策（所有窗口必须遵守）

1. **零回归优先**：每个功能都用 config 开关，默认关 / 默认 FIXED，保证现有 110+ 测试不变。先 TDD 写测试再实现，覆盖率不低于现状。
2. **#3 父块存储 = 独立父块**（已与用户确认）：子块只存 `parentId`；父块正文放独立 store（内存 `Map` + pgvector `document_parent` 表），检索命中子块后按 `parentId` 查父块回填给 LLM。不反贴到子块（避免冗余）。
3. **#3 语义分块**：作为 `strategy=SEMANTIC` 第三策略；句子级 embedding + 相邻相似度跌破阈值处断块；测试用假 `EmbeddingClient` 注入，不依赖真端点。
4. **#1a 多模态 = 真·多模态向量空间**（已与用户确认有真 VL embedding 端点）：图片/PDF 解析后用 VL embedding 进**同一向量空间**，文本 query 可召回图像 chunk；不是"图转文再 embedding"。VL 端点配置需向用户索取或读环境变量。
5. **风格对齐现有代码**：手写、不引 LangChain；构造器保留无依赖重载方便测试；日志用现有 `log.info("xx_event key=val ...")` 风格；中文注释只写 WHY。
6. **每个功能收尾三件事**：① 建独立 `docs/<feature>.md` 记动机/方案/决策/量化；② 更新本 devlog 表状态 + 进度记录；③ 通知用户在轨道 C 窗口回填简历。

## 关键事实基线（开工前现状，2026-06 探查）

- 分支：`exp/prompt-caching-probe`（实验分支，非主干）。
- Embedding 可插拔：`LLM_EMBEDDING_MODEL` 环境变量切换，yml 默认 `text-embedding-3-small`；rerank 走 SiliconFlow `BAAI/bge-reranker-v2-m3`。
- 简历技术栈写 `Qwen3-VL-Embedding-8B（4096维）`——用户确认有「真 VL embedding 端点」（能吃图片输入），#1a 将据此实现真·多模态向量空间，使该措辞落地为真。
- 分块现状：固定长度 600 + 滑动窗口 overlap 100，无高级策略。
- 评估现状：Java 侧 Hit@K/Recall@K/MRR；Python 侧 RAGAS（faithfulness/context precision）；评估集 26 题弱标注（仅 expectedPhrase 关键词，无标准答案）。
- 无答案兜底：已实现（`NO_ANSWER="根据现有资料无法回答。"`，scoreThreshold 默认 0.3）。
- 增量索引：已实现（版本隔离 + 旧版软删 DELETED + 重 embedding）。
- 检索候选可配：recallTopK=50（单路召回）→ RRF 融合 → topK=3（rerank 前后），rrfK=60。

---

## 功能规格（跨窗口唯一事实源）

### #3 分块策略升级（轨道 A，第 1 个）

**目标**：分块策略可插拔 + Markdown 结构感知 + 独立父块 Parent-Document + 语义分块。

**配置**（`RagProperties` 新增 `Chunk` 嵌套，prefix `rag.chunk`）：
- `strategy`: `FIXED`（默认，= 现有固定窗口，零回归）/ `MARKDOWN` / `SEMANTIC`
- `parent-document.enabled`: bool，默认 false。开启后检索回填父块。
- `semantic.threshold`: double，相邻句相似度断块阈值（如 0.6），仅 SEMANTIC 用。
- 保留现有 `chunkSize=600` / `chunkOverlap=100`（子块尺寸）。

**架构**：
- 新建 `chunk/TextSplitter` 接口：`ChunkResult split(documentId, filename, text, config)`；`ChunkResult` 含 `List<DocumentChunk> children` + `List<ParentBlock> parents`。
- `FixedWindowSplitter`：把 `DocumentService.chunkText` 现有 while 循环原样搬过来（行为不变）；无父块。
- `MarkdownSplitter`：按标题层级（`#`/`##`/…）切成 section，每个**叶子 section = 一个父块**；子块 = section 内按 `chunkSize` 固定窗口切，并在子块正文前加标题面包屑（如 `标题：安装 > 环境要求\n<正文>`）帮助检索；子块 `parentId` = section id。
- `SemanticSplitter`：句子切分 → 逐句 embedding（注入 `EmbeddingClient`）→ 相邻句 cosine 跌破 `threshold` 处断块；可与父块结合（父块=语义大段）或独立。
- `DocumentChunk` 加 `parentId`（可空，null = 无父块走原逻辑）。
- 新建父块存储：`ParentBlock`（parentId/documentId/content/version/租户权限元数据）+ `ParentStore` 接口 + `InMemoryParentStore` + `JdbcParentStore`（`@ConditionalOnProperty vectorstore.backend=pgvector`，表 `document_parent`）。
- `DocumentService`：`chunkText` 改为按 strategy 取 `TextSplitter`；入库时把父块写 `ParentStore`、子块带 `parentId`；`delete`/版本清理同步删父块。
- `RagSearchResult` 加 `parentId`；`RagAskService.buildContext`：当 `parent-document.enabled` 且子块有 parentId，按 parentId 查 `ParentStore` 取父块正文拼上下文，**按 parentId 去重**（一个父块只出现一次），sources 仍指向命中的子块。
- `db/init/01_schema.sql`：加 `document_parent` 表（parent_id PK、document_id、tenant_id、content、version、权限列对齐 document_chunk）。

**测试（先写）**：Markdown 标题切分/面包屑/超长 section 回退、父块分组、SEMANTIC 断块（假 EmbeddingClient）、buildContext 父块回填+去重、FIXED 默认零回归（跑全量测试）。

**量化**：用 `eval` 对比 FIXED vs MARKDOWN+parent 的 Hit@K / context precision（轨道 B 评估集就绪后更准）。

---

### #4 Contextual Retrieval（轨道 A，第 2 个）

**目标**：每个子块 embedding 前，用 LLM 生成一句"它在全文/父块中的定位"前缀，拼到待 embedding 文本前，提升召回。复用 Prompt Caching 压成本。

**配置**：`rag.contextual.enabled` 默认 false（零回归）。

**架构**：
- 新建 `ContextualEnricher`：输入(父块或全文 + 子块) → LLM 产出 ≤50 字上下文前缀；把"父块/全文"作为可缓存前缀注入 `cache_control`（复用现有 Prompt Caching 机制），同一文档多个子块共享缓存。
- `DocumentService` embedding 路径：开启时，embedding 文本 = `前缀 + 原文`；**展示/回填仍用原文**（`DocumentChunk` 可加 `embeddingText` 或单独记录，原 `content` 不变）。
- 失败降级：LLM 失败时回退为不加前缀，不阻断入库。

**测试**：假 `LlmClient` 验证前缀拼接、缓存前缀注入、失败降级；开关关闭时行为不变。

**量化**：`eval` 对比开启前后 Hit@K / recall。

---

### #1a 多模态 RAG（轨道 A，第 3 个）

**目标**：真·多模态向量空间。图片/PDF 解析后用 **VL embedding 端点**进同一向量空间，文本 query 可召回图像 chunk。

**前置**：需向用户索取 VL embedding 端点配置（base-url/model/key），或读 `LLM_EMBEDDING_*` 环境变量。开工先问用户。

**架构**：
- 上传支持扩展：新增 `pdf`/`png`/`jpg`/`jpeg`（改 `isSupportedFilename`/`isSupportedContentType`）。
- PDF 解析：引入 PDFBox，抽取文本 + 内嵌图片（或整页渲染为图）。文本走原文本链路；图片走图像 embedding。
- `EmbeddingClient`：加 `embedImage(bytes/url)` 走 VL 端点；与文本同维（4096，schema 已是 vector(4096)）。
- `DocumentChunk` 加 `modality`（TEXT/IMAGE）+ 图片引用（路径/objectKey）；IMAGE chunk 的 `content` 存图片说明/alt 或 OCR 摘要供展示，向量来自图像本身。
- schema 加 `modality` 列（默认 TEXT，零回归）。
- 检索链路不变（同一向量空间）；buildContext 对 IMAGE chunk 用其引用/说明拼上下文。

**测试**：假 EmbeddingClient（图像分支）、PDF 文本+图片抽取、上传类型校验、IMAGE chunk 召回与展示；纯文本路径零回归。

**量化**：构造图文混排小语料，验证"文本 query 召回正确图片"。

---

### #7 多轮会话 RAG（轨道 A，第 4 个）

**目标**：结合对话历史的 conversational query rewrite（指代消解，"它的价格"→"X 的价格"）+ 历史压缩；不只单轮改写。

**配置**：`rag.query-rewrite` 已有 `enabled`；新增 `conversation.enabled` / `history-turns`（默认保守值，零回归）。

**架构**：
- `QueryRewriterService` 加 history-aware 重写：取最近 N 轮，LLM 把追问改写成自包含 query。
- 会话历史存储：复用 `agent/ConversationState` 或新建轻量 `ConversationHistoryStore`（内存 Map，key=conversationId）。
- `RagAskService.ask` 接受 `conversationId`/history 入参并透传 rewriter；controller 增 `conversationId`。
- 历史压缩：超过 N 轮时 LLM 摘要早期轮次。

**测试**：假 LlmClient 验证指代消解、历史压缩、无 conversationId 时回退单轮（零回归）。

---

### #6 扩展评估集（轨道 B，第 1 个）

**目标**：`eval` 评估集从 26 题扩到 50-100 题，**补标准答案**（不只 expectedPhrase 关键词），提升量化统计可信度。

**架构**：
- 扩 `questions.json`（或 `docs/rag-eval-cases.json`）：每题加 `groundTruthAnswer` + `expectedDocIds`/`expectedPhrase`。语料覆盖：规范 FAQ、口语化长问、多跳、无答案（应触发兜底）。
- 校验脚本/测试确保格式合法、引用的文档存在。

**测试**：评估集 schema 校验；`RagEvalService` 读新字段不报错。

**依赖**：#5 的 context recall / answer correctness 需要这里的 `groundTruthAnswer`，故 #6 先做。

---

### #5 评估维度补全（轨道 B，第 2 个）

**目标**：在现有 faithfulness/context precision/Hit@K 基础上，补 **answer relevancy / context recall / answer correctness**，形成"检索 + 生成"双侧完整评估。

**架构**：
- `eval/ragas_eval.py`：加 RAGAS 的 `answer_relevancy` / `context_recall` / `answer_correctness`（后两者用 #6 的 groundTruth）。
- `RagEvalService`：若有 Java 侧聚合，补对应指标输出。
- 输出三方案（FIXED / MARKDOWN+parent / +contextual）对比表，供简历与博客引用。

**测试**：python 评估脚本 smoke（小样本）；Java 侧指标聚合单测。

---

## 进度记录

### 2026-06 · #9-11 简历排版精简 ✅

**动机**：RAG 项目 bullet 多达 10 条，招聘者略读时重点被稀释；pgvector 一条是技术堆砌的巨段（visibility/GIN/数组运算符/HNSW/RRF 全塞一句）可读性差；顺序像开发日志流水而非按影响力。

**改动**（`resume-zh.tex` RAG itemize，10→7 条）：
1. 合并：Langfuse 可观测性折入「RAGAS 评估」条（同属"看得见 RAG 内部"）；「用量审计」折入「成本优化」条；「检索增强全链路」的 sources 可追溯点折入头条、端到端延迟折入 pgvector 条。
2. 拆分：pgvector 巨段拆成「向量库持久化（迁移+HNSW+开关零回归）」+「权限过滤 SQL 下推」两条可读 bullet。
3. 合并：「Agent 工作流」+「工程化（不用 LangChain）」合一条（不用 LangChain 的理由正是 Agent 的企业需求）。
4. 重排：按影响力——混合检索+精排（headline 量化）→ RAGAS 评估 → Query 改写量化 → 向量库持久化 → 权限下推 → 成本优化 → Agent+工程化。

**结果**：xelatex 编译通过，3 页保持不变，无 error/warning。

**最终 7 条 bullet**：混合检索+精排 / RAGAS+Langfuse / Query 改写量化 / 向量库持久化 / 权限 SQL 下推 / 成本优化+用量审计 / Agent 工作流与工程化。

> 注：后续 #1a/#3/#4/#7 等新功能落地后，将以"替换或合并较弱项"方式回填，维持 6-7 条纪律（见 task #9）。

### 2026-06 · #6 扩展评估集 ✅（轨道 B，worktree `E:\yhl\RAG-eval`，分支 `feat/rag-eval`）

**动机**：原评估集仅 26 题且弱标注（只有 `expectedPhrase` 关键词，无标准答案），统计可信度低，也无法支撑 #5 的 answer correctness / context recall（需完整 ground truth）。

**改动**：
1. `src/test/resources/measurement/questions.json`：26 → **53 题**，每题新增 `groundTruthAnswer`（完整标准答案，基于 corpus 事实）+ `category`（题型）。
2. 覆盖分布：**faq 25 / colloquial 9 / multi_hop 11 / no_answer 8**。
   - colloquial = 口语化长问（啰嗦的自然提问）；multi_hop = 多跳（≥2 篇文档）；no_answer = 语料里没有答案、应触发兜底「根据现有资料无法回答。」。
3. 新增 `EvalSetSchemaTest`（纯 JUnit，无 Spring）：校验题量 50–100、必填字段、`category` 合法、`expectedKeys` 全部指向 corpus 中真实存在的文档、no_answer 题无引用且 ground truth 为兜底串、多跳题 ≥2 篇、题目去重、分布下限；并用「严格 ObjectMapper」验证 `RagEvalService.loadCases` 读带 `groundTruthAnswer` 的文件不报错。
4. `RagEvalCase` 加 `groundTruthAnswer` 字段；`RagMeasurementHarnessTest.Question` DTO 加 `groundTruthAnswer`/`category`，RAGAS 导出的 `reference` 改为优先用完整标准答案（为 #5 铺路）；`docs/rag-eval-cases.json` 三例补 `groundTruthAnswer` 作示例。

**零回归**：仅动 eval/评估集相关文件，未碰 `DocumentService`/`RagProperties` 等轨道 A 热点文件。全量测试 **118 通过 / 0 失败 / 2 跳过**（跳过的是 env-gated 的 measurement 与 langfuse 集成）。

**给 #5 的接口**：`groundTruthAnswer` 已就绪，可在 `ragas_eval.py` 接 `answer_correctness` / `context_recall`，并按 `category` 拆维度统计（尤其 no_answer 兜底命中率）。

### 2026-06 · #5 评估维度补全 ✅（轨道 B，worktree `E:\yhl\RAG-eval`，分支 `feat/rag-eval`）

**动机**：原 RAGAS 只有 faithfulness / answer_relevancy / context_precision 三个 reference-free 指标，只能评"生成忠实度/切题/排序"，缺「检索召回完整性」和「答案对不对」，无法形成检索+生成双侧闭环。

**改动**（仅 `eval/`）：
1. `eval/ragas_eval.py`：补 **context_recall + answer_correctness** 两个 reference-based 指标（answer_relevancy 原已在）。
   - `load_ground_truth()` 从 `questions.json` 读 `question→groundTruthAnswer`；`load_samples()` 按 question 把完整标准答案 join 进每行 `reference`——judge 与冻结数据集解耦，换标准答案不必重跑 Java 采集。
   - `has_references()` 全行有 reference 才启用 reference-based 指标；`metric_names()`/`build_metrics()` 拆分纯函数选指标 + 构造对象，后者用 try/except 兼容 ragas 0.1/0.2 命名（`LLMContextRecall`/`ContextRecall`、`AnswerCorrectness`/`answer_correctness`）。
   - 输出新增 `eval/ragas-comparison.md`（markdown 对比表，供博客/简历）。
2. 新增 `eval/test_ragas_eval.py`：**离线**单测（不联网、不 import ragas，靠把 ragas import 关进函数体内实现）——校验 join、reference 富化、选指标逻辑。`_smoke.py` 同步改用 `build_metrics`。

**量化（已测真实数据，26 题，judge=Qwen2.5-72B + bge-m3）**：

| mode | faithfulness | answer_relevancy | context_precision |
| --- | --- | --- | --- |
| vector | 0.8540 | 0.3703 | 0.6528 |
| hybrid | **0.9484** | **0.5261** | 0.8431 |
| hybrid_rerank | 0.9171 | 0.4429 | **0.8889** |
| vector_rewrite | 0.8807 | 0.3618 | 0.8111 |

→ hybrid 相对 vector 三项全面提升；rerank 把 context_precision 顶到 0.889。

**两点诚实标注（未造数）**：
- `context_recall` / `answer_correctness` 代码就绪，但需判官 LLM 联网评测才有数值，本机无 API/网络，标「待重跑」（`python eval/ragas_eval.py` 即出）。
- devlog #5 要的 `FIXED / MARKDOWN+parent / +contextual` 三方案对比依赖轨道 A 的 **#3/#4**（待开始），表框架已在 `docs/eval-metrics.md` 就位，待其落地填入。

**零回归**：仅动 `eval/` + 新增 `docs/eval-metrics.md`，未碰任何 Java 主代码与轨道 A 热点文件；Java 全量 **118 通过 / 2 跳过**，Python 离线测试全通过。

### 2026-06 · #3 分块策略升级 ✅（轨道 A，主工作树 `E:\yhl\RAG`，分支 `exp/prompt-caching-probe`）

**动机**：原分块只有「固定 600 + overlap 100」一种，逐字符切——会切碎 Markdown 结构（标题与正文分家），且小块检索精度高 vs 大块上下文完整二者不可兼得。详见 [chunking-strategy.md](chunking-strategy.md)。

**改动**：
1. 新建 `com.yhl.rag.chunk` 包：`TextSplitter` 接口 + `ChunkConfig`/`ChunkResult`/`ParentBlock` + 三实现（`FixedWindowSplitter` 搬原 while 循环零变更 / `MarkdownSplitter` 标题切 section + 面包屑 + 叶子 section 父块 / `SemanticSplitter` 逐句 embedding 相邻相似度断块）+ `ChunkingService` 按 strategy 分派。
2. 独立父块存储：`ParentStore` 接口 + `InMemoryParentStore`（默认）+ `JdbcParentStore`（`@ConditionalOnProperty pgvector`，新表 `document_parent`）；`document_chunk` 加 `parent_id` 列让子块 parentId 在 pgvector 往返。
3. `RagProperties.Chunk`（`rag.chunk.strategy` / `parent-document.enabled` / `semantic.threshold`），默认 FIXED + 父块关。
4. `DocumentService` 入库/更新按 strategy 取 splitter、父块写 store、子块带 parentId；`delete`/版本清理同步删父块。`chunkText(...)` 公开 API 固定走 FIXED 保持逐字节不变。
5. `RagSearchResult.parentId` + `RagSearchService` 透传；`RagAskService.buildContext` 在 `parent-document.enabled` 时按 parentId 回填父块正文、**按 parentId 去重**，sources 仍指向命中子块。
6. `InMemoryVectorStore.copyChunk` / `PgVectorStore` 读写保留 parentId。

**关键决策**（均与用户确认）：独立父块不反贴；语义只切子块不产父块；手写不引 LangChain；全构造器保留无依赖重载。

**零回归 + 测试**：新增 17 个测试（FixedWindow 行为/ID 稳定、Markdown 标题/面包屑/超长回退/容器标题、Semantic 断块假 client、InMemoryParentStore 增删查、父块回填+去重、MARKDOWN 入库 parentId 往返 + 删除清父块）。全量 **127 通过 / 0 失败 / 2 跳过**（原 110+，本轮 +17）。

**量化**：FIXED vs MARKDOWN+parent 的 Hit@K / context precision 严格对比，待轨道 B 评估集（#6 已就绪 53 题）接 #3 重跑，避免弱标注得不可靠数字；当前已单测级确证三策略与回填/去重行为正确。表框架见 `docs/eval-metrics.md` + `chunking-strategy.md §4`。

### 2026-06 · #4 Contextual Retrieval ✅（轨道 A，主工作树 `E:\yhl\RAG`，分支 `exp/prompt-caching-probe`）

**动机**：分块换来检索精度，但小子块脱离上下文后语义残缺（「需要 JDK 17」属安装还是升级？「点击申请」申请什么？）。借鉴 Anthropic 2024《Contextual Retrieval》：embedding 前用 LLM 看着父块/全文为子块补一句定位说明前置进去，向量同时编码「局部内容+全局定位」提召回。详见 [contextual-retrieval.md](contextual-retrieval.md)。

**改动**：
1. 新建 `com.yhl.rag.document.ContextualEnricher`：`buildEmbeddingText(content, contextSource)` → LLM 产 ≤50 字定位前缀，拼成「前缀\n原文」；把 contextSource（父块/全文）放进 system instructions，复用 `LlmClient` 对 system 块注入的 `cache_control`，同一文档多子块共享可缓存前缀压成本。
2. `RagProperties.Contextual`（`rag.contextual.enabled`，默认 false，零回归）+ application.yml `RAG_CONTEXTUAL_ENABLED`。
3. `DocumentService.embedChunks` 接入：上下文源「父块优先（子块 parentId 命中）、全文兜底」；embedding 文本 = enricher 产物，**`chunk.content` 保持原文不落库前缀**，回填/展示零影响。`update`/`processIngestTask` 两条入库路径一处接入两处生效；公开 `chunkText` 不走 embedding 不受影响。
4. 构造器：@Autowired 主构造器加 `ContextualEnricher`（10 参）；保留旧 9 参重载（默认无 client 的 enricher，contextual 关时永不调用），存量测试零改动。

**降级（与 QueryRewriter 一致）**：开关关 / 无 client / 子块或上下文空 / LLM 异常 / 返回空 → 全部回退原文、不阻断入库。contextSource 超 8000 字截断防撑爆输入。

**零回归 + 测试**：先 TDD。新增 8 个测试——`ContextualEnricherTest`（6：前缀拼接、缓存前缀注入 system+子块进 user、异常/空白/空源/无 client 降级、开关关不调 LLM）+ `DocumentContextualEmbeddingTest`（2：开启时 embed 入参带前缀且 content 不含前缀、关闭时纯原文零回归）。全量 **135 通过 / 0 失败 / 2 跳过**（原 127，本轮 +8）。

**量化（待联网重跑，不造数）**：本机无 embedding/LLM 端点。复现配方见 `contextual-retrieval.md §5`——同评估集同检索模式，仅切 `RAG_CONTEXTUAL_ENABLED` 跑两遍 `RagMeasurementHarnessTest` 对照 Hit@K/Recall@K/MRR（建议在 MARKDOWN+父块 + 缓存开启下跑，同时量召回增益与省下的 token）。对齐 `docs/eval-metrics.md` 的「+contextual」栏。

### 2026-06 · #1a 多模态 RAG ✅（轨道 A，主工作树 `E:\yhl\RAG`，分支 `exp/prompt-caching-probe`）

**动机**：简历技术栈写了 `Qwen3-VL-Embedding-8B（4096维）`，但此前没有任何代码真正用到图像输入——只是预留的维度声明。本功能让图片/PDF 内嵌图经 VL embedding 进与文本**同一向量空间**，文本 query 可直接召回图像 chunk，是**真多模态**而非"图转文再 embedding"。详见 [multimodal-rag.md](multimodal-rag.md)。

**开工前确认（用户答复）**：① VL 端点复用 `LLM_EMBEDDING_*`；② 初答"OpenAI 兼容 `/v1/embeddings` 传图片 dataURL"，③ PDF 抽**内嵌图片对象**（非整页渲染）；④ 图片字节用**内存 ImageStore** 引用（demo）。

**实测纠偏（关键，后续追加）**：本机其实**有网络 + 有可用 key**，"待联网"是误判。实测两条真实端点：
- **SiliconFlow `Qwen/Qwen3-VL-Embedding-8B`（OpenAI `/v1/embeddings`，4096 维）**：把图片 dataURL **当文本串** embedding——**伪多模态**（红色文本反而离蓝图更近 0.302<0.354）。这正是简历"4096维"的来源，但该 API 不能真做图像检索。
- **DashScope `qwen3-vl-embedding`（原生多模态，`input.contents:[{image}]`，2560 维）**：**真多模态**，实测红色文本·红图=0.738 ≫ ·蓝图=0.433、蓝色文本·蓝图=0.683 ≫ ·红图=0.443，纯文本 query 召回正确图片。
- 故最终走 **DashScope 原生**，新增 `llm.embedding-style=openai|dashscope-multimodal`（默认 openai 零回归），`.env.local` 配 dashscope-multimodal + `qwen3-vl-embedding`。

**改动**：
1. `EmbeddingClient`：新增 `embedImage(bytes, mime)` + `llm.embedding-style` 两风格——`openai`（dataURL 当 input 字符串，复用文本 `/v1/embeddings`）/ `dashscope-multimodal`（图片作为 `input.contents:[{image}]` 投 DashScope 原生端点，取 `output.embeddings[0].embedding`）。两风格下文本与图像同模型同空间。OpenAI 响应记录补 `@JsonIgnoreProperties` 兼容 SiliconFlow 的 `index` 字段。
2. 新建 `PdfParser`（PDFBox 3.0.5）：`PDFTextStripper` 抽正文 + 遍历 XObject 抽 `PDImageXObject` 渲染 PNG；单图失败只跳过不中断。`ImageStore`：内存 `imageRef → {bytes,mime}`，供展示/回填，删除/换版本时释放。
3. `DocumentChunk` 加 `modality`（TEXT 默认，零回归）+ `imageRef`；`pgvector` schema 加 `modality`/`image_ref` 列（默认 TEXT/null，旧数据免迁移）；两个 VectorStore 读写带上。
4. `DocumentService`：`rag.multimodal.enabled`（默认 false）；开关开时扩展上传类型 pdf/png/jpg/jpeg；`parseRawContent` 按类型分派（图片→单 IMAGE chunk / PDF→文本+内嵌图 / 其余→UTF-8 文本）；`embedChunks` 按 modality 分派 `embed` vs `embedImage`；IMAGE chunk 接在文本子块 index 之后；`update`/`processIngestTask` 两条入库路径同源接入；删除/换版本释放 ImageStore。
5. 检索/问答层透传：`RagSearchResult` / `RagSource` 加 `modality`/`imageRef`，前端可展示召回到的图片来源；`RagAskService.buildContext` 对 IMAGE chunk 用其说明文本拼上下文（无 OCR），sources 带图片引用。
6. `DocumentService.getImage(imageRef)` 公开，供控制器按 ref 取回原图展示。

**关键决策**（与用户确认）：真多模态向量空间（决策第 4 条）；复用文本 embed 路径保证同空间；抽内嵌图非整页渲染；demo 内存图片存储；`rag.multimodal.enabled` 默认关、`modality` 默认 TEXT 双重保零回归。

**零回归 + 测试**：先 TDD，新增 14 个测试——`EmbeddingClientImageTest`(4：dataURL 构造 + DashScope 请求体构造) / `PdfParserTest`(2) / `ImageStoreTest`(2) / `DocumentMultimodalIngestTest`(5：图片建 IMAGE chunk 走 embedImage 不走 embed、纯文本零回归、开关关拒绝图片、开关开接受、删除释放图片) / `RagMultimodalRetrievalTest`(1：**图文混排小语料下纯文本 query 召回 IMAGE chunk**，同空间近邻，source 带 modality=IMAGE+imageRef) + `EmbeddingClientLiveIT`(env-gated，真实 DashScope)。全量 **149 通过 / 0 失败 / 2 跳过**（原 135，本轮 +14）。

**量化（已用真实 DashScope `qwen3-vl-embedding` 实测）**：文本/图像同 2560 维同空间；红色文本·红图=0.738 ≫ ·蓝图=0.433，蓝色文本·蓝图=0.683 ≫ ·红图=0.443——纯文本 query 召回正确图片，真实端点验证。SiliconFlow OpenAI 端点对照为伪多模态（见上文纠偏）。复现见 `multimodal-rag.md §5`。

> **简历提醒（重要，已纠偏）**：技术栈里的 `Qwen3-VL-Embedding-8B（4096维）` 来自 SiliconFlow，**4096 维属实**，但其 OpenAI `/v1/embeddings` **做不了真图像检索**（伪多模态）。要让"图文多模态召回"成立，图像路径须走 **DashScope `qwen3-vl-embedding`（2560 维）**。回填简历时：要么把多模态模型/维度改成 DashScope 2560，要么写清"文本 4096 维(SiliconFlow) + 图像召回走 DashScope 2560"，**不要拿 SiliconFlow 4096 维当多模态检索依据**。

### 2026-06 · #7 多轮会话 RAG ✅（轨道 A，主工作树 `E:\yhl\RAG`，分支 `exp/prompt-caching-probe`）

**动机**：单轮 query 改写只看当前一句，但真实多轮对话里追问几乎都带指代（"它的价格""那个怎么弄"）——第 2 轮单独检索时代词没有文档锚点，向量召回打空。需结合历史做**指代消解式改写**（"它的内存呢"→"ThinkPad X1 的内存大小"），并在会话变长时**压缩早期轮次**控 token。详见 [conversational-rag.md](conversational-rag.md)。

**改动**：
1. `QueryRewriterService` 加两方法（不引新依赖，复用既有 LlmClient）：`rewrite(question, ConversationHistory)`（结合摘要+最近轮指代消解）+ `summarizeHistory(existingSummary, turns)`（早期轮压成滚动摘要）。
2. 新建轻量内存会话存储（不复用退款 Agent 的 `ConversationState`，语义不符）：`ConversationTurn`（user+assistant）/ `ConversationHistory`（summary + recentTurns）/ `ConversationHistoryStore`（`ConcurrentHashMap`，key=`userId:conversationId`，进程内不持久化）。
3. `RagProperties.QueryRewrite.Conversation`（`rag.query-rewrite.conversation.enabled` / `history-turns`=5 / `summary-threshold`=10），默认关；application.yml 加三个 `RAG_QUERY_REWRITE_CONVERSATION_*` 环境变量。
4. `RagAskService` 新增 `ask(question, conversationId, debug)`，旧 `ask` 委派传 `conversationId=null`（零回归）；会话开启且有 conversationId 时：检索前用历史指代消解，答案生成后**自动记录本轮 (question, answer)**，累计超阈值则压缩早期轮（摘要失败返回 null 则放弃压缩、保留完整历史）。
5. `RagAskRequest` 加可选 `conversationId`，`RagController.ask` 透传。构造器：新增 10 参 @Autowired 主构造器（加 `ConversationHistoryStore`），保留 9/8/6 参旧重载（默认 `new ConversationHistoryStore()`），存量测试零改动。

**关键决策**（遵守锁定决策第 1/5/6 条 + 用户确认）：配置嵌在 `query-rewrite` 下、`query-rewrite.enabled` 作总开关；`ask` 内自动记录历史（客户端每轮只带同一 conversationId 即可）；新建轻量 store 不复用 ConversationState；手写不引 LangChain、全构造器保留无依赖重载。

**降级（与单轮 QueryRewriter / ContextualEnricher 一致）**：会话关/总开关关/空历史 → 回退单轮改写；改写 LLM 异常/空 → 返回原追问；摘要 LLM 异常/空 → 放弃压缩保历史；无 conversationId → 全程不碰 store，逐字节等同改造前。

**零回归 + 测试**：先 TDD，新增 17 个测试——`ConversationHistoryStoreTest`(5) + `ConversationalQueryRewriteTest`(9：指代消解+历史进 prompt、空历史/会话关回退单轮、总开关关不调 LLM、改写异常/空降级、摘要压缩/无轮次/异常返回 null) + `RagAskConversationalTest`(3：多轮第二轮用消解后 query+两轮入历史、无 conversationId 走单轮不记录、会话关不记录)。全量 **166 通过 / 0 失败 / 2 跳过**（原 149，本轮 +17）。

**量化（已用真实端点实测，不造数）**：单测确证四类行为正确。另建 `conversational-questions.json`（12 组指代追问对）+ `ConversationalRetrievalHarnessTest`，入库 51 篇真实语料，对 turn-2 比 baseline（原样检索）vs treatment（会话改写后检索）的 Hit@3：**baseline 50.0%（6/12）→ treatment 100.0%（12/12），+50pp**。6 个 baseline 漏召正是代词最重的追问，改写成自包含 query 后全召回。详见 `conversational-rag.md §7` + 仓库根 `conversational-measurement-report.md`。
> 诚实标注：embedding 走真端点 DashScope `qwen3-vl-embedding`(2560)；chat 因 `.env.local` 默认 relay（co.yes.vg gpt-5.5）当时 **HTTP 402 无订阅**，改写/答案改用 DashScope `qwen-plus` 兼容端点跑通（换强模型只会更准）；n=12 为演示量级、非统计严谨。

> **简历提醒**：#7 多轮会话 RAG 已落地，可在轨道 C 简历窗口回填——「conversational query rewrite + 指代消解 + 历史摘要压缩」是面大模型应用岗的高信号点，维持 6-7 条 bullet 纪律（替换/合并较弱项）。量化未联网，回填时只写已确证的能力，**不要编 Hit@K 数字**。
