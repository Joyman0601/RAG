# RAG 增强 · 各窗口提示词（复制粘贴即用）

> 每段是一个独立窗口的开场提示词。先看顶部「使用说明」。
> 唯一事实源：`docs/rag-enhancement-devlog.md`（设计决策 + 功能规格）。

## 使用说明

- **轨道 A（核心，串行）**：在 `E:\yhl\RAG` 主工作树，一次只开一个窗口，按 #3 → #4 → #1a → #7 顺序。做完一个、我 review、再开下一个。
- **轨道 B（评估，并行）**：先建独立 worktree 再开窗口，和轨道 A 同时跑：
  ```
  cd /e/yhl/RAG && git worktree add ../RAG-eval -b feat/rag-eval
  ```
  然后新窗口打开 `E:\yhl\RAG-eval`，按 #6 → #5。完成后合回主干。
- **轨道 C（简历，并行）**：在 `E:\yhl\tmp\resume\resume`，每个功能落地后回填。
- 每段提示词已自包含；新窗口无需我重新解释代码——它会先读 devlog。

---

## 【轨道 A · #3】分块策略升级

```
你在 E:\yhl\RAG（Java 17 + Spring Boot 3 的企业知识库 RAG 项目）工作。
请先读 docs/rag-enhancement-devlog.md，重点看「锁定的设计决策」和「#3 分块策略升级」规格——那是本任务的唯一事实源，按它执行。

任务：实现 #3 分块策略升级（分块策略可插拔 + Markdown 结构感知 + 独立父块 Parent-Document + 语义分块）。
关键已定决策：父块用「独立父块存储」（子块只存 parentId，父块正文放独立 store：内存 Map + pgvector document_parent 表）；语义分块作为 strategy=SEMANTIC，测试用假 EmbeddingClient；默认 strategy=FIXED 保证现有 110+ 测试零回归。

要求：
1. 先 TDD 写测试再实现，不要降低现有覆盖率，全量测试必须通过。
2. 遵守 devlog「锁定的设计决策」第 1/2/3/5 条（零回归、独立父块、语义分块假 client、手写不引 LangChain、构造器保留无依赖重载、日志风格、注释只写 WHY）。
3. 完成后三件事：① 建 docs/chunking-strategy.md 记动机/方案/决策/量化；② 更新 devlog 表状态为完成 + 写进度记录；③ 提醒我去简历窗口回填。
4. 动手前如有架构歧义，先问我再写。
```

---

## 【轨道 A · #4】Contextual Retrieval

```
你在 E:\yhl\RAG（Java 17 + Spring Boot 3 RAG 项目）工作。
先读 docs/rag-enhancement-devlog.md 的「锁定的设计决策」和「#4 Contextual Retrieval」规格，按它执行。
前置：#3 已完成（分块/父块已就绪），本任务在其之上。

任务：实现 Contextual Retrieval——每个子块 embedding 前用 LLM 生成一句"它在父块/全文中的定位"前缀拼到待 embedding 文本前，提升召回；复用现有 Prompt Caching 把"父块/全文"作为 cache_control 缓存前缀压成本。展示/回填仍用原文。开关 rag.contextual.enabled 默认 false（零回归）。LLM 失败降级为不加前缀。

要求：
1. 先 TDD（假 LlmClient 验证前缀拼接/缓存前缀注入/失败降级；开关关闭行为不变），全量测试通过。
2. 遵守 devlog「锁定的设计决策」。
3. 完成后：① 建 docs/contextual-retrieval.md；② 更新 devlog；③ 提醒我回填简历；④ 用 eval 给出开启前后 Hit@K/recall 对比（评估集若已扩展则用新集）。
4. 有歧义先问我。
```

---

## 【轨道 A · #1a】多模态 RAG

```
你在 E:\yhl\RAG（Java 17 + Spring Boot 3 RAG 项目）工作。
先读 docs/rag-enhancement-devlog.md 的「锁定的设计决策」和「#1a 多模态 RAG」规格，按它执行。

任务：实现真·多模态 RAG——图片/PDF 解析后用 VL embedding 端点进同一向量空间，文本 query 可召回图像 chunk（不是图转文再 embedding）。扩展上传类型(pdf/png/jpg)、PDFBox 抽文本+图、EmbeddingClient 加图像 embedding、DocumentChunk 加 modality(TEXT/IMAGE)+图片引用、schema 加 modality 列(默认 TEXT 零回归)。

开工第一步：问我要 VL embedding 端点配置（base-url / model / api-key），或确认走哪个 LLM_EMBEDDING_* 环境变量。拿到再动手。

要求：
1. 先 TDD（假 EmbeddingClient 图像分支、PDF 文本+图抽取、上传类型校验、IMAGE chunk 召回与展示；纯文本路径零回归），全量测试通过。
2. 遵守 devlog「锁定的设计决策」（尤其第 4 条：真多模态向量空间）。
3. 完成后：① 建 docs/multimodal-rag.md；② 更新 devlog；③ 提醒我回填简历，并提醒简历技术栈里的 Qwen3-VL-Embedding 此刻才真正落地；④ 用图文混排小语料验证"文本 query 召回正确图片"。
4. 有歧义先问我。
```

---

## 【轨道 A · #7】多轮会话 RAG

```
你在 E:\yhl\RAG（Java 17 + Spring Boot 3 RAG 项目）工作。
先读 docs/rag-enhancement-devlog.md 的「锁定的设计决策」和「#7 多轮会话 RAG」规格，按它执行。

任务：实现结合对话历史的 conversational query rewrite（指代消解，如"它的价格"→"X 的价格"）+ 历史压缩；不只单轮改写。扩 QueryRewriterService 支持 history-aware 重写；会话历史存储复用 agent/ConversationState 或新建轻量 ConversationHistoryStore；RagAskService.ask + controller 接受 conversationId 并透传；超 N 轮 LLM 摘要早期轮次。开关默认保守、无 conversationId 时回退单轮（零回归）。

要求：
1. 先 TDD（假 LlmClient 验证指代消解/历史压缩/无 conversationId 回退单轮），全量测试通过。
2. 遵守 devlog「锁定的设计决策」。
3. 完成后：① 建 docs/conversational-rag.md；② 更新 devlog；③ 提醒我回填简历。
4. 有歧义先问我。
```

---

## 【轨道 B · 准备】建 worktree（先跑一次）

```
在 E:\yhl\RAG 执行：git worktree add ../RAG-eval -b feat/rag-eval
然后用新窗口打开 E:\yhl\RAG-eval 跑下面 #6、#5。完成后 git 合回主干。
```

## 【轨道 B · #6】扩展评估集

```
你在 E:\yhl\RAG-eval（RAG 项目的 eval 专用 git worktree，分支 feat/rag-eval）工作。
先读 docs/rag-enhancement-devlog.md 的「锁定的设计决策」和「#6 扩展评估集」规格，按它执行。

任务：把评估集从 26 题扩到 50-100 题并补标准答案。扩 questions.json（或 docs/rag-eval-cases.json）：每题加 groundTruthAnswer + expectedDocIds/expectedPhrase；语料覆盖规范 FAQ / 口语化长问 / 多跳 / 无答案(应触发兜底)。加格式校验确保合法、引用文档存在。

要求：
1. 先写校验测试再扩数据；RagEvalService 读新字段不报错；全量测试通过。
2. 只动 eval/评估集相关文件，别碰 DocumentService/RagProperties 等轨道 A 热点文件（避免合并冲突）。
3. 完成后：① 更新 devlog 表状态 + 进度记录；② 告诉我题量和覆盖分布。
```

## 【轨道 B · #5】评估维度补全

```
你在 E:\yhl\RAG-eval（分支 feat/rag-eval）工作。
先读 docs/rag-enhancement-devlog.md 的「锁定的设计决策」和「#5 评估维度补全」规格，按它执行。
前置：#6 已完成（questions.json 有 groundTruthAnswer）。

任务：在现有 faithfulness/context precision/Hit@K 上补 answer relevancy / context recall / answer correctness（后两者用 #6 的 groundTruth），形成检索+生成双侧完整评估。改 eval/ragas_eval.py 加这三个 RAGAS 指标；若 Java 侧有聚合则补输出；产出三方案(FIXED / MARKDOWN+parent / +contextual)对比表供简历与博客引用。

要求：
1. 先写 smoke/单测再实现；只动 eval 相关文件；全量测试通过。
2. 完成后：① 建 docs/eval-metrics.md 记指标与对比表；② 更新 devlog；③ 把对比表发我，供简历回填量化。
```

---

## 【轨道 C】简历回填（每个功能落地后）

```
你在 E:\yhl\tmp\resume\resume（简历 repo，主文件 resume-zh.tex），目标岗位是 Java 后端 + 大模型应用，RAG 是主打。
先读 E:\yhl\RAG\docs\rag-enhancement-devlog.md 看已落地的功能和量化结果，再读对应的 docs/<feature>.md 拿细节。

任务：把刚落地的功能【填功能名，如 #3 分块/父块】回填进 resume-zh.tex 的 RAG 项目。规则：
1. 维持 6-7 条 bullet 纪律——新 bullet 要么替换较弱项、要么合并，别让它无限变长。
2. 只写真实、已量化的内容（数字来自 devlog/docs），不夸大。
3. 改完用 xelatex 编译验证通过、页数不失控（目标 3 页）：
   xelatex -interaction=nonstopmode -halt-on-error resume-zh.tex
4. 告诉我改了哪条、为什么。
```
