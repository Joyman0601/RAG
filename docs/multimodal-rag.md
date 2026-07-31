# 多模态 RAG：图片/PDF 用 VL embedding 进同一向量空间

> 一句话总结：图片与 PDF 内嵌图经 **VL embedding 端点**（DashScope `qwen3-vl-embedding`）打成向量，与文本子块进**同一向量空间**——文本 query 可直接召回图像 chunk，而**不是**"先用模型把图描述成文字、再 embedding 那段文字"。开关 `rag.multimodal.enabled` 默认关，纯文本路径零回归。

> ⚠️ **踩坑实录（务必读）**：不是所有"VL embedding"端点都能真做图像 embedding。实测两条路：
> - **DashScope 原生多模态** `qwen3-vl-embedding`（`input.contents:[{image}]`，2560 维）→ **真多模态**，跨模态召回干净（见 §5 实测数字）。
> - **SiliconFlow 的 OpenAI 兼容 `/v1/embeddings`** + `Qwen/Qwen3-VL-Embedding-8B`（4096 维）→ 把图片 dataURL **当文本串** embedding，是**伪多模态**：返回 4096 维向量看着"成功"，但编码的是 base64 字符串而非图像，颜色都分不开。**勿用于图像。**
> 区别在 API 形状：DashScope 把图片作为结构化 `image` content 项；OpenAI `/embeddings` 的 `input` 只认字符串，多模态模型也吃不到图。

## 0. 为什么是"真"多模态

把图片接进 RAG 有两条路：

1. **图转文再 embedding**（伪多模态）：用一个 caption/OCR 模型把图描述成文字，再对那段文字做普通文本 embedding。检索时其实是"文字 query × 文字描述"，图片信息在描述那一步就被有损压扁了——描述没提到的视觉细节，永远召不回。
2. **真多模态向量空间**（本实现）：用 VL embedding 模型直接对**图像本身**打向量，落在与文本 embedding **同一个**向量空间。文本 query 的向量和图像向量直接算 cosine，"文字找图"是一次普通的同空间近邻检索。

本项目走第 2 条。前提是有一个能**同时**吃文本和图片输入、输出同维向量的 VL embedding 端点（这里复用 `LLM_EMBEDDING_*`，模型为 Qwen3-VL-Embedding，4096 维，schema 已是 `vector(4096)`）。

## 1. 数据流

```
上传 png/jpg ──────────────► 整张图 = 1 个 IMAGE chunk
上传 pdf ──► PDFBox 解析 ─┬─► 正文文本 ──► 现有文本分块链路（FIXED/MARKDOWN/SEMANTIC）= TEXT chunk
                          └─► 内嵌图片对象 ──► 每张图 = 1 个 IMAGE chunk
上传 txt/md ──────────────► 现有文本链路（完全不变）

TEXT chunk  ──► EmbeddingClient.embed(text)        ┐
IMAGE chunk ──► EmbeddingClient.embedImage(bytes)  ┴─► 同一向量空间 ──► VectorStore
```

检索链路**完全不变**：query 文本 embedding 后在同一向量空间里做近邻（vector / hybrid / rerank 都照旧），命中的 IMAGE chunk 和 TEXT chunk 一起按分数排序返回。

## 2. 关键组件

### 2.1 `EmbeddingClient`：两种 embedding 风格

由 `llm.embedding-style` 切换（默认 `openai`，零回归）：

- **`openai`**：文本 `embed(text)` 走 OpenAI 兼容 `/v1/embeddings`（`input` 传字符串）；`embedImage` 把图片编码成 data URL 后**也当字符串**走同一端点。仅当该端点**真能吃图片**时才是真多模态；像 SiliconFlow 这类纯文本 embedding 端点会把 dataURL 当文本 embed（伪多模态，见顶部踩坑）。
- **`dashscope-multimodal`**：文本与图像都走 DashScope 原生多模态端点，`embedImage` 把图片作为结构化 `input.contents:[{image:dataURL}]` 投出，VL 模型对**图像本身**打向量——真多模态的落点。

```java
// dashscope-multimodal 风格下，文本与图像走同一 VL 模型、同一端点、同一向量空间：
embed(text)            -> {"model":"qwen3-vl-embedding","input":{"contents":[{"text": text}]}}
embedImage(bytes,mime) -> {"model":"qwen3-vl-embedding","input":{"contents":[{"image":"data:...;base64,..."}]}}
// 响应取 output.embeddings[0].embedding；文本与图像同维（2560），可直接 cosine 比较。
```

### 2.2 `PdfParser`（PDFBox，手写不引框架）

- 文本：`PDFTextStripper` 抽正文，走原有文本分块。
- 图片：遍历每页 `PDResources` 的 XObject，命中 `PDImageXObject` 的渲染成 PNG 字节。单张图解码失败只跳过该图、不中断整篇解析。
- 决策：**抽取内嵌图片对象**而非整页渲染——只 embed 文档里真实的图片，避免纯文字页也产出一张图污染召回。

### 2.3 `ImageStore`（demo 内存实现）

IMAGE chunk 不把图片字节塞进向量库，只存一个 `imageRef`。`ImageStore` 按 ref 持有字节+mime，供展示/回填取回。`put` 返回 ref，删除/换版本时 `remove` 释放。生产可整体替换为磁盘目录或对象存储而不动调用方——`imageRef` 即 objectKey 的占位。

### 2.4 `DocumentChunk` 的 `modality` + `imageRef`

- `modality`：`TEXT`（默认，零回归）/ `IMAGE`。入库 embedding 按它分派 `embed` vs `embedImage`。
- `imageRef`：IMAGE chunk 的图片引用；TEXT chunk 为 null。
- IMAGE chunk 的 `content` 存一句展示用说明（如 `[图片] org-chart.png` 或 `[图片] guide.pdf 第2页`），**向量来自图像本身**，content 仅供 source 预览与 BM25 命中。

## 3. 零回归设计

- `rag.multimodal.enabled` 默认 **false**：关时上传仍仅限 txt/md/markdown，`parseRawContent` 一律按 UTF-8 文本走——与本功能引入前逐字节一致。
- `modality` 列默认 `TEXT`，存量 chunk 行为不变；pgvector schema 加 `modality`/`image_ref` 两列（默认 TEXT / null），旧数据无需迁移。
- 构造器保留无 `PdfParser/ImageStore` 的重载，存量测试零改动。
- 检索/问答层对 IMAGE chunk 与 TEXT chunk 一视同仁，未触碰原打分与过滤逻辑。

## 4. 测试（先 TDD）

| 测试 | 覆盖点 |
| --- | --- |
| `EmbeddingClientImageTest` | data URL 构造（mime + base64，空 mime 兜底）；DashScope 请求体构造（text/image content 项） |
| `PdfParserTest` | PDF 抽文本 + 内嵌图；纯文字 PDF 不产图 |
| `ImageStoreTest` | put/get/缺失 ref |
| `DocumentMultimodalIngestTest` | 图片上传建 IMAGE chunk 且走 `embedImage`（不走 `embed`）；纯文本零回归只走 `embed`；关开关时图片上传被拒、开时被接受；删除释放 ImageStore |
| `RagMultimodalRetrievalTest` | **图文混排小语料：纯文本 query `组织架构图` 召回 IMAGE chunk**（同空间近邻），source 带 modality=IMAGE + imageRef |
| `EmbeddingClientLiveIT`（env-gated） | **真实 DashScope `qwen3-vl-embedding`**：文本/图像同维（2560）+ 颜色文本召回匹配图片；用于挡伪多模态端点 |

全量 **149 通过 / 0 失败 / 2 跳过**（原 135，本轮 +14；2 跳过为 env-gated 的 measurement 与 langfuse 集成；`EmbeddingClientLiveIT` 默认禁用，仅设 `EMB_IT_API_KEY` 时联网跑）。

## 5. 量化（已用真实 DashScope 端点实测）

`EmbeddingClientLiveIT`（env-gated）用真实 `qwen3-vl-embedding` 跑了纯红/纯蓝两张 64×64 图 + 对应中英文文本，验证同空间 + 跨模态召回：

| 指标 | 实测 |
| --- | --- |
| 文本维度 / 图像维度 | **2560 / 2560**（同一向量空间） |
| 红色文本 · 红图 | **0.738** |
| 红色文本 · 蓝图 | 0.433 |
| 蓝色文本 · 蓝图 | **0.683** |
| 蓝色文本 · 红图 | 0.443 |

→ 颜色匹配的「文本×图片」相似度明显高于不匹配的，**纯文本 query 召回正确图片**得到真实端点验证。

对照（同 IT 跑 SiliconFlow `Qwen/Qwen3-VL-Embedding-8B` + openai 风格）：返回 4096 维但红色文本反而离蓝图更近（0.302 < 0.354）——印证其 `/v1/embeddings` 把图片 dataURL 当文本串 embedding，**伪多模态**。该 IT 的颜色断言正是用来挡住这种端点。

**复现**：
```
set -a && . ./.env.local && set +a   # 提供 DASHSCOPE_API_KEY
EMB_IT_API_KEY=$DASHSCOPE_API_KEY \
EMB_IT_BASE_URL=https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding \
EMB_IT_MODEL=qwen3-vl-embedding EMB_IT_STYLE=dashscope-multimodal \
mvn test -Dtest=EmbeddingClientLiveIT
```
端到端跑应用：`.env.local` 已配 `LLM_EMBEDDING_STYLE=dashscope-multimodal` + `RAG_MULTIMODAL_ENABLED=true`，上传含图 pdf/png 后用图里信息发文本 query 即可召回图片（source.modality=IMAGE，imageRef 取回原图）。

> **模型选型注意**：`Qwen3-VL-Embedding-8B（4096维）` 来自 SiliconFlow，**4096 维属实**，但该 OpenAI 端点**不能真做图像检索**。若要让"图文多模态召回"的表述成立，图像路径需走 **DashScope `qwen3-vl-embedding`（2560 维）**——文档描述要么把多模态模型/维度改成 DashScope，要么明确"图像召回走 DashScope，文本 4096 维走 SiliconFlow"，别把 SiliconFlow 4096 维直接当多模态检索的依据。
