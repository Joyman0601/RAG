# 分块策略升级：可插拔 + Markdown 结构感知 + 独立父块 + 语义分块

> 一句话总结：把原来「固定 600 字 + 100 overlap」一条路走到黑的分块，重构成**可插拔策略**（FIXED / MARKDOWN / SEMANTIC），并落地 **Parent-Document（独立父块）**——检索用小子块保精度、喂 LLM 用大父块保完整。默认仍是 FIXED，现有 127 个测试零回归。

## 0. 为什么动分块

RAG 的召回质量，七分在分块。原实现只有一种：固定长度滑动窗口（`chunkSize=600`、`overlap=100`），逐字符切。它的两个硬伤：

1. **切碎语义结构**。一篇 Markdown FAQ，标题 `## 退款政策` 和它的正文可能被切到两个 chunk 里；检索命中正文却丢了「这是退款政策」这个上下文。
2. **粒度两难**。chunk 切小，向量检索精度高（query 和 chunk 更聚焦），但喂给 LLM 时上下文不完整；chunk 切大，上下文完整但检索精度下降（一个大块里混了多个话题，向量被稀释）。

这一轮就是奔着这两个问题去的，做三件事：策略可插拔、Markdown 结构感知、独立父块。

## 1. 架构：TextSplitter 可插拔

新建 `com.yhl.rag.chunk` 包，核心是一个接口：

```java
public interface TextSplitter {
    ChunkStrategy strategy();
    ChunkResult split(String documentId, String filename, String text, ChunkConfig config);
}
```

`ChunkResult = List<DocumentChunk> children + List<ParentBlock> parents`。三个实现：

- **`FixedWindowSplitter`**：把原 `DocumentService.chunkText` 的 while 循环原样搬过来，行为逐字节不变、chunkId 稳定可复算（增量索引去重依赖它）。无父块。
- **`MarkdownSplitter`**：按 `#`/`##`/… 标题切 section，**有正文的 section = 一个独立父块**；子块在 section 内按固定窗口切，并在正文前加**标题面包屑**（`标题：安装 > 环境要求\n<正文>`）帮助检索定位。纯容器标题（只有子标题、无自身正文）不产父块；无标题文档整体作一个 section。
- **`SemanticSplitter`**：句子切分 → 逐句 embedding → 相邻句 cosine 跌破 `threshold` 处断块，让语义连续的句子留在同一子块。

`ChunkingService` 按 `config.strategy()` 取对应 splitter。新增策略只需注册一处。

配置（`rag.chunk`，prefix 已接环境变量）：

```yaml
rag:
  chunk:
    strategy: FIXED            # FIXED | MARKDOWN | SEMANTIC
    parent-document:
      enabled: false           # 开启后检索回填父块
    semantic:
      threshold: 0.6           # 相邻句断块阈值，仅 SEMANTIC
```

## 2. 独立父块（Parent-Document）

这是本轮的重点决策。Parent-Document 的目标是同时吃到「小块检索精度」和「大块上下文完整」：**检索命中小子块，回填它所属的大父块给 LLM**。

存储有两种流派：

- 反贴：把父块正文复制进每个子块。简单，但冗余——一个父块 N 个子块就存 N 份。
- **独立父块存储（本项目选型）**：子块只存 `parentId`，父块正文放独立 store；检索命中后按 `parentId` 查父块回填。无冗余，父块更新只动一处。

落地：

- `ParentBlock`（parentId / documentId / content / version + 与子块对齐的租户和权限元数据）。
- `ParentStore` 接口，两套实现按 `vectorstore.backend` 装配，与 `VectorStore` 同款：
  - `InMemoryParentStore`（默认 `memory`，`@ConditionalOnProperty matchIfMissing`）。
  - `JdbcParentStore`（`pgvector`），新表 `document_parent`，权限列对齐 `document_chunk`，便于同样的 SQL 过滤。
- `document_chunk` 加 `parent_id` 列，让子块的 `parentId` 在 pgvector 后端也能往返（检索回填需要）。
- 入库时父块写 `ParentStore`、子块带 `parentId`；`delete` / 版本清理同步删父块（`deleteByDocumentId` / `deleteByDocumentIdAndVersion`），不留孤儿。

检索回填在 `RagAskService.buildContext`：当 `parent-document.enabled` 且命中子块有 `parentId`，按 `parentId` 取父块正文拼进上下文，**按 `parentId` 去重**（一个父块只出现一次），**sources 仍指向命中的子块**（可追溯到精确命中点，父块正文只进 LLM 上下文不进 sources）。

## 3. 锁定的设计决策

1. **零回归优先**。默认 `strategy=FIXED`、`parent-document.enabled=false`，公开的 `chunkText(...)` 永远走 FIXED（不读 strategy），存量调用方与测试行为逐字节不变。全量 127 测试通过（原 110+，本轮新增 17）。
2. **独立父块，不反贴**（已与用户确认）。理由见上：去冗余、单点更新。
3. **语义只切子块、不产父块**（已与用户确认）。devlog 原写「可与父块结合或独立」，取「独立」——父块回填主要服务 MARKDOWN 的结构边界，语义边界不如标题清晰，强行做父块收益不明且测试更重。
4. **手写、不引 LangChain**。句子切分、面包屑、RRF 这类逻辑都在仓库里看得见、可单测，符合「企业里不愿把检索黑箱外包给框架」的取向。
5. **构造器保留无依赖重载**。`DocumentService` / `RagAskService` / `ChunkingService` 都加了便捷构造器自带 `InMemoryParentStore` 和 splitter，老测试一行不用改。
6. **日志 / 注释风格对齐**。沿用 `log.info("document_chunk ... strategy=MARKDOWN chunkCount=.. parentCount=..")`；中文注释只写 WHY。

## 4. 量化

可量化的对比指标是 **FIXED vs MARKDOWN+parent** 的 **Hit@K / context precision**。本轮先把能力与零回归做实，严格的 eval 对比留待**评估集扩展就绪后**跑，避免用现有 26 题弱标注集得出不可靠数字。

当前已确证（单测级）：

- MARKDOWN 对 `# 安装 / ## 环境要求 / ## 下载` 切出 3 个有正文的 section → 3 父块 3 子块，子块带面包屑 `标题：安装 > 环境要求`，`parentId` 正确指向父块。
- 超长 section 回退为多子块共享同一 `parentId`（父块仍是完整 section 正文）。
- SEMANTIC 在相邻句相似度跌破阈值处断块（假 `EmbeddingClient` 注入，不依赖真端点）。
- 回填+去重：3 命中子块（c1/c2 同父 p1、c3 无父）→ 上下文出现父块正文一次、c3 原文一次，sources=[c1, c3]。
- MARKDOWN 入库 → 子块 `parentId` 全部可在 `ParentStore` 查到；删除文档 → 父块同步清除。

> 待办：评估集就绪后，补 FIXED / MARKDOWN+parent / SEMANTIC 三方案的 Hit@K、context precision、context recall 对比表，回填本节。
