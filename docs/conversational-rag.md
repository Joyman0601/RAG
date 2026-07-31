# 多轮会话 RAG：结合历史的指代消解改写 + 历史压缩

> 一句话总结：在单轮 query 改写之上，加一层**对话式改写**——结合最近若干轮历史把追问里的指代（"它的价格""那个怎么弄"）消解成自包含检索 query；会话变长时用 LLM 把早期轮次压成滚动摘要控制 token。开关 `rag.query-rewrite.conversation.enabled` 默认关，**无 conversationId / 空历史时回退单轮**，零回归。

## 0. 为什么需要它

单轮改写（见 `QueryRewriterService` 既有 `rewrite(String)`）只看当前这一句。但真实多轮对话里追问几乎都带指代：

- 第 1 轮：「ThinkPad X1 的价格是多少？」→ 答「9999 元」。
- 第 2 轮：「**它的**内存呢？」

第 2 轮单独拿去检索，"它"没有任何文档锚点，向量召回基本打空。人能秒懂"它=ThinkPad X1"，是因为脑子里存着上文。要让检索也懂，就得**把历史喂进去做指代消解**，把「它的内存呢」改写成「ThinkPad X1 的内存大小」再检索。

第二个问题是**历史无限增长**：对话几十轮后，把全部历史塞进改写 prompt 既贵又超长。所以超过阈值时要把早期轮次**压缩成摘要**，只保留最近 N 轮原文 + 一段滚动摘要。

## 1. 架构

复用现有 `QueryRewriterService`（已持有 `LlmClient` + `RagProperties`），新增两个方法，不引新依赖：

```java
// 结合历史的指代消解改写。会话关/总开关关/历史空 → 回退单轮 rewrite(question)；失败降级原问题。
String rewrite(String question, ConversationHistory history);

// 把已有摘要 + 较早轮次压成新摘要。无可压缩轮次 → 返回 existingSummary；LLM 失败/空 → 返回 null。
String summarizeHistory(String existingSummary, List<ConversationTurn> turnsToSummarize);
```

会话历史存储新建**轻量内存 store**（`ConversationState` 是退款 Agent 专用，含 orderId/工具态，语义不符，故另建）：

- `ConversationTurn(userMessage, assistantMessage)`：一轮问答。
- `ConversationHistory(summary, recentTurns)`：滚动摘要 + 仍保原文的最近若干轮。
- `ConversationHistoryStore`：`ConcurrentHashMap`，key=`userId:conversationId`（租户/用户隔离对齐 `ConversationStateService` 约定）。`get/append/replace/clear`，进程内、不持久化（demo 级）。

改写时喂给 LLM 的 user 消息形如：

```
对话历史摘要：
<summary>                ← 仅当有摘要时

最近对话：
用户：ThinkPad X1 的价格是多少
助手：ThinkPad X1 售价 9999 元

当前追问：它的内存呢
```

system instructions 是固定的对话式改写说明，LLM 只输出改写后的 query（如「ThinkPad X1 的内存大小」）。

## 2. 问答链路接入（RagAskService）

新增 `ask(question, conversationId, debugRequested)`；旧 `ask(question)` / `ask(question, debug)` 委派进来传 `conversationId=null`（零回归）。

```
conversational = conversation.enabled && question!=null && conversationId 非空
retrievalQuery = conversational
    ? rewrite(question, store.get(userId, conversationId))   // 指代消解
    : rewrite(question)                                       // 单轮（与改造前完全一致）
... 检索 + 生成 ...
if (conversational) recordTurn(userId, conversationId, question, answer)  // 自动记录本轮
```

- **自动记录**：拿到 `conversationId` 且会话开关开时，`ask` 在成功生成答案后把 `(question, answer)` 追加进 store，下一轮即可指代消解——客户端每轮只需带同一 `conversationId`，无需额外调用。
- **历史压缩**：`recordTurn` 追加后，若累计轮数 > `summaryThreshold`，把最旧的 `size - historyTurns` 轮交给 `summarizeHistory` 压成摘要，只保留最近 `historyTurns` 轮原文；摘要失败（返回 null）则**放弃本次压缩、保留完整历史**，绝不丢上下文。
- **接入唯一**：改写与记录都在 `ask` 主路径，controller 只多透传一个 `conversationId`。

Controller / DTO：`RagAskRequest` 加可选 `conversationId`，`RagController.ask` 透传。

## 3. 配置（嵌在 query-rewrite 下，总开关分层）

```yaml
rag:
  query-rewrite:
    enabled: false                  # 单轮改写总开关
    conversation:
      enabled: false                # 多轮指代消解，需上面 enabled=true 作前提
      history-turns: 5              # 改写纳入 & 压缩后保留的最近轮数
      summary-threshold: 10         # 累计超过此轮数触发早期轮次摘要压缩
```

放在 `rag.query-rewrite` 下是因为对话式改写是 query 改写的子能力：`query-rewrite.enabled` 是总开关，`conversation.enabled` 是子开关。两者都开才做指代消解；只开总开关走单轮；都关则不改写。

## 4. 零回归与降级

默认 `conversation.enabled=false`，`ask` 永不触碰 store，检索 query 走 `rewrite(question)`，与改造前逐字节一致。开启后任一不利条件都降级到「更弱但正确」的行为：

| 条件 | 行为 |
| --- | --- |
| 会话开关关 / 总开关关 | 回退单轮 `rewrite(question)`（总开关也关则原样返回） |
| 无 conversationId / 空历史 | 回退单轮改写（首轮天然无历史，即走此路） |
| 改写 LLM 抛异常 / 返回空 | 降级返回**原追问**，`WARN rag_conversational_rewrite_fallback/_empty` |
| 摘要 LLM 抛异常 / 返回空 | `summarizeHistory` 返回 null → 放弃压缩、保留完整历史 |

降级理念与 `QueryRewriterService` 单轮、`ContextualEnricher` 一致：增强失败回退到未增强的正确路径，绝不让一次 LLM 抖动卡死问答。

## 5. 测试（先写后实现，TDD，假 LlmClient）

- `ConversationHistoryStoreTest`（5 例）：追加保序、user/conversation 隔离、未知/空 conversationId 返回空、replace 换摘要+最近轮、空 conversationId 追加是 no-op。
- `ConversationalQueryRewriteTest`（9 例）：
  - **指代消解**：历史 + 追问"它的内存呢" → 返回"ThinkPad X1 的内存大小"，且断言历史与追问都进了喂 LLM 的 user 消息；
  - **回退单轮**：空历史 / 会话开关关 → 走单轮改写指令（断言指令文案）；总开关关 → 原样返回且 `verifyNoInteractions`；
  - **降级**：改写 LLM 抛异常 / 返回空白 → 返回原追问；
  - **摘要**：压缩早期轮、无轮次返回原摘要、LLM 抛异常返回 null。
- `RagAskConversationalTest`（3 例，真 `RagAskService` + 假 client，按指令文案分派改写/答案）：
  - **多轮 + 自动记录**：第 2 轮检索用的是指代消解后的自包含 query，两轮都进历史；
  - **无 conversationId**：走单轮、历史不写入；
  - **会话开关关**：带 conversationId 也不记录。

全量 **166 通过 / 0 失败 / 2 跳过**（原 149，本轮 +17）。

## 6. 设计取舍

- **为什么不复用 `ConversationState`？** 它是退款 Agent 的会话态（currentOrderId / pendingConfirmationId / lastTool），字段与「问答轮历史」语义不符，硬塞会耦合两个领域。新建轻量 store 各管各的更干净——devlog 决策也给了「复用或新建」两选项。
- **为什么记录原问题而非改写后的 query？** 历史要服务下一轮的指代消解，得保留用户**原话**（"它""那个"出现的真实语境）和助手答案里的实体；存改写后的 query 会丢失对话自然性。
- **为什么摘要失败就放弃压缩？** 压缩是「删原文换摘要」，若摘要没生成成功就删原文，等于凭空丢上下文。宁可让历史暂时偏长（下轮再试压缩），也不丢信息。
- **为什么默认关？** 每轮多一次改写 LLM 调用、会话变长还要摘要调用，有成本与延迟。默认关保零回归，需要多轮体验时再开。

## 7. 量化（端到端多轮命中率，已用真实端点实测）

单测确证指代消解 / 压缩 / 回退 / 降级四类行为正确；端到端增益用**真实语料 + 真实端点**实测。

**评估集**：现有 `questions.json`（#6，53 题）多为单轮，无法量多轮，故另建 `src/test/resources/measurement/conversational-questions.json`——12 组「指代追问对」：turn-1 自包含建立实体（"病假需要提交什么材料？"），turn-2 用代词/省略追问同一实体（"那它的工资怎么算？"），期望文档为同一篇。

**harness**：`ConversationalRetrievalHarnessTest`（env-gated `MEASUREMENT_RUN=true`）。入库全部 51 篇真实语料后，对每组：先真实跑 turn-1 拿答案写进会话历史，再对 turn-2 比两条检索路径的 Hit@K——

- **baseline**：turn-2 原样检索（指代未消解）；
- **treatment**：用历史做 conversational rewrite 把指代消解成自包含 query 再检索。

**结果（2026-06-26 实测，语料 51 篇 / 12 组 / top-K=3）**：

| 路径 | turn-2 Hit@3 | 命中数 |
| --- | --- | --- |
| baseline（追问原样检索） | 50.0% | 6/12 |
| **treatment（会话改写后检索）** | **100.0%** | **12/12** |

→ 指代消解把追问轮检索命中率 **+50pp**。6 个 baseline 漏召的正是代词最重的追问（"那它的工资""它需要上传什么证明""那男员工的呢""它需要经过谁审批""它的报告怎么领取""请假当天它还算吗"）——改写成"病假期间的工资计算方式""男员工陪产假有多少天"等自包含 query 后全部召回。逐组明细见仓库根 `conversational-measurement-report.md`。

```bash
# 复现（env 见 .env.local；MEASUREMENT_RUN=true 解禁 harness）
MEASUREMENT_RUN=true mvn -o test -Dtest=ConversationalRetrievalHarnessTest
```

> **诚实标注**：① 本轮 embedding 走 DashScope `qwen3-vl-embedding`（2560 维，真端点）；**改写/答案的 chat 模型**因当时使用的中转 relay 订阅过期返回 **HTTP 402**，改用 DashScope **`qwen-plus`** 兼容端点跑通——换强模型只会更准，不影响"指代消解提召回"的结论方向。② n=12 是小样本，演示量级而非统计严谨；扩样只需往 `conversational-questions.json` 加对。
