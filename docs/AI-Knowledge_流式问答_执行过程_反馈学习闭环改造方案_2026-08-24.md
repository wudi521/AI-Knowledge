# AI-Knowledge 流式问答、执行过程与反馈学习闭环改造方案

**版本**：V1.0  
**日期**：2026-08-24  
**适用阶段**：Patent AI Knowledge Base P0 收口 / Controlled Pilot 前  
**目标**：把当前“能回答”的知识问答链路升级为“可交互、可解释、可反馈、可持续优化”的企业级 AI 知识产品。

---

## 1. 方案结论

本轮增加三类能力：

1. **Streaming Chat**：问答改为 SSE 流式输出，用户在 100~500ms 内看到系统已开始处理，生成内容逐步输出，不再长时间等待完整 JSON。
2. **可解释执行过程**：对话窗口显示真实 Query Pipeline Stage，例如问题识别、目标文档锁定、检索、重排、证据筛选、生成、验证、修复。展示的是“可审计执行过程”，不是模型内部隐藏思维链。
3. **Feedback Learning Loop**：每条 AI 回答支持“有用/无用”反馈，并记录失败原因；后续形成 Bad Case、Eval Case、高频问题聚类、FAQ Candidate、人工审核、FAQ Fast Path 的知识运营闭环。

其中：

- **P0 必做**：Streaming、Stage Event、Thinking UI、反馈采集 API/数据、Evidence/Citation/Trace 联动、错误处理与 P0 Final Gate。
- **P1 执行**：反馈分析、Bad Case Mining、Query Clustering、FAQ Candidate、人工审核发布、FAQ Fast Path、Prompt/Retrieval 优化。
- **暂不直接做**：将用户“点赞/点踩”直接用于模型微调或自动发布 FAQ。

---

## 2. 为什么现在必须做

当前问答链路虽然已经具备 Conversation、Knowledge Base Context、Query Planner、EXACT_METADATA、EXACT_CLAIM、SCOPED_RAG、HYBRID_RAG、Evidence、Citation、Trace 等基础能力，但用户体验仍存在三个关键缺口：

- 请求是“一次性返回”，复杂查询需要等待较长时间，用户无法判断系统是否卡死；
- 后端内部已经有较丰富的检索和验证阶段，但对话页看不到，用户无法理解“系统查了什么、为什么这样答”；
- 系统没有持续收集真实用户对回答质量的反馈，无法形成自动化的质量运营与知识沉淀闭环。

这三个问题不解决，系统更像“工程 RAG 平台”；解决之后，才开始接近真实企业 AI 知识产品。

---

## 3. 产品目标

### 3.1 用户侧目标

一次正常问答应呈现为：

```text
用户：
申请号 202311042981.1 的核心技术方案是什么？

知识助手：
  ✓ 已理解问题：技术方案查询
  ✓ 已锁定专利：202311042981.1
  ✓ 正在目标专利内检索
  ✓ 找到 12 个候选知识单元
  ✓ 重排后保留 3 条证据
  ✓ 正在生成回答
  ✓ 证据校验通过

申请号 202311042981.1 的核心技术方案是…… [C1][C2]

引用来源（3）
C1  CN 122604134 A · 权利要求1 · 第2页
C2  CN 122604134 A · 说明书 · 第3页

👍 有用    👎 无用
查看完整执行链路 →
```

### 3.2 系统侧目标

每一次 Query 都具备：

```text
conversationId
queryId
messageId
traceId
route
intent
stage events
evidence[]
citations[]
feedback
latency
model calls
```

从而形成：

```text
Query
  ↓
可观测执行
  ↓
Answer + Evidence
  ↓
User Feedback
  ↓
Quality Analytics
  ↓
Bad Case / FAQ Candidate / Eval Case
  ↓
优化知识、检索、Prompt、路由
```

---

## 4. 总体架构

```text
┌──────────────────────────────────────────┐
│            Knowledge Workbench           │
│                                          │
│  Conversation                            │
│  Stage/Thinking Panel                    │
│  Streaming Answer                        │
│  Evidence / Citation                     │
│  Useful / Not Useful                     │
└───────────────────┬──────────────────────┘
                    │ SSE
                    ▼
┌──────────────────────────────────────────┐
│              Chat Stream API             │
│                                          │
│ conversation / stage / evidence / delta  │
│ verification / done / error              │
└───────────────────┬──────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────┐
│            Query Orchestrator             │
│                                          │
│ ANALYZE → ROUTE → SCOPE → RETRIEVE       │
│ → RERANK → EVIDENCE → GENERATE → VERIFY  │
└───────┬──────────────────────┬───────────┘
        │                      │
        ▼                      ▼
 Query Trace Store        Evidence Store
        │                      │
        └──────────┬───────────┘
                   ▼
           Feedback Service
                   │
          ┌────────┴─────────┐
          ▼                  ▼
      Bad Case          Positive Case
          │                  │
          ▼                  ▼
       Eval Case        FAQ Candidate
          │                  │
          └────────┬─────────┘
                   ▼
             Human Review
                   │
                   ▼
          Published FAQ Fast Path
```

---

# 5. P0-A：Streaming Chat

## 5.1 技术选择

本阶段优先使用 **SSE（Server-Sent Events）**，不因为聊天而引入 WebSocket。

原因：

- 当前通信主要是“服务端持续向客户端输出”；
- SSE 与 HTTP 基础设施、Gateway、鉴权体系更容易兼容；
- 天然支持 event stream；
- 前端实现和故障恢复成本较低；
- 后续真正出现双向实时协作需求时再评估 WebSocket。

保留现有同步接口用于兼容和自动化测试：

```text
POST /admin-api/chat/chat/send
```

新增流式接口：

```text
POST /admin-api/chat/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

首次问题：

```json
{
  "kbId": 6,
  "message": "申请号 202311042981.1 的核心技术方案是什么？"
}
```

后续问题：

```json
{
  "conversationId": 108,
  "message": "它主要解决什么问题？"
}
```

后端必须继续从 Conversation 恢复 KB / Domain / Tenant / User Context，不允许客户端覆盖已有 Conversation 的 KB。

---

## 5.2 SSE 事件协议

统一事件类型：

```text
conversation
stage
evidence
delta
verification
done
error
```

### conversation

连接建立并完成 Query Context 创建后尽早返回：

```text
event: conversation
data: {
  "conversationId":108,
  "queryId":"qry_xxx",
  "traceId":"q_xxx",
  "kbId":6,
  "domainCode":"PATENT"
}
```

### stage

```text
event: stage
data: {
  "stage":"ANALYZE",
  "status":"RUNNING",
  "label":"正在理解问题",
  "elapsedMs":0
}
```

完成：

```text
event: stage
data: {
  "stage":"SCOPE_FILTER",
  "status":"DONE",
  "label":"已锁定申请号 202311042981.1",
  "elapsedMs":31,
  "summary":{
    "applicationNo":"202311042981.1",
    "documentId":66
  }
}
```

### evidence

建议在最终 Evidence 集合稳定后推送一次：

```text
event: evidence
data: {
  "count":3,
  "items":[...]
}
```

### delta

只用于最终 Answer 内容增量：

```text
event: delta
data: {"content":"该专利的核心技术方案是"}
```

### verification

```text
event: verification
data: {
  "status":"PASSED",
  "repairCount":0
}
```

### done

最终权威状态：

```text
event: done
data: {
  "conversationId":108,
  "messageId":3021,
  "queryId":"qry_xxx",
  "traceId":"q_xxx",
  "route":"SCOPED_RAG",
  "answerable":true,
  "answer":"...",
  "citations":["C1","C2"],
  "evidence":[...],
  "confidence":0.93,
  "latencyMs":8120,
  "degraded":false
}
```

### error

```text
event: error
data: {
  "code":"QUERY_TIMEOUT",
  "message":"本次查询处理时间过长，请稍后重试",
  "retryable":true,
  "traceId":"q_xxx"
}
```

---

## 5.3 流式实现原则

1. **SSE 不改变 Query Pipeline 业务语义**，只改变事件输出方式。
2. `done` 是最终权威状态；前端不可仅依赖拼接后的 delta 作为最终持久化结果。
3. Evidence/Citation 最终校验不通过时，不能把未经验证的流式草稿当成最终答案永久保存。
4. 如果系统采用“先流式草稿，再 Verify”，UI 应明确显示“正在校验”，最终 `done.answer` 可以修正草稿。
5. 为减少用户看到答案被大幅回滚，推荐：
   - 对确定性 EXACT 路由直接快速流式输出；
   - SCOPED/HYBRID 的生成阶段流式输出，但 Repair 最多一次；
   - 如果业务对“答案回滚”非常敏感，可采用小 Buffer 后再向 UI 推 delta。
6. 支持用户取消：前端 AbortController → 服务端 Cancellation Token / interrupted flag → 后续 Generate/Verify 尽可能停止。
7. 用户双击发送不得创建两个 Query；前后端都需要幂等 / in-flight guard。

---

# 6. P0-B：可解释执行过程（Thinking UI）

## 6.1 核心定义

对话页展示的不是模型内部“思维链”，而是**真实、可审计、可验证的执行过程**。

允许展示：

- 问题意图/Route；
- 锁定的 Knowledge Base / Document / Patent Identifier；
- 是否使用 Exact Lookup、BM25、Vector、Rerank；
- 候选数量与最终 Evidence 数量；
- 是否 Generate；
- 是否 Verify / Repair；
- 各阶段耗时；
- 是否发生降级。

禁止展示：

- 隐藏 CoT；
- 完整 System Prompt；
- Access Token / API Key；
- Authorization Header；
- 内部异常堆栈；
- 不必要的用户敏感数据；
- Model Provider 私有响应原文。

---

## 6.2 Stage 标准模型

统一 Stage 枚举：

```text
ANALYZE
ROUTE
REWRITE
SCOPE_FILTER
DOC_LOOKUP
BM25
VECTOR
FUSION
RERANK
EVIDENCE
GENERATE
VERIFY
REPAIR
ANSWER
```

状态：

```text
PENDING
RUNNING
DONE
SKIPPED
FAILED
DEGRADED
```

每个 Stage 统一字段：

```json
{
  "stage":"RERANK",
  "status":"DONE",
  "label":"已完成相关性重排",
  "startTime":"...",
  "endTime":"...",
  "elapsedMs":806,
  "inputSummary":{
    "candidateCount":12
  },
  "outputSummary":{
    "selectedCount":3
  },
  "errorCode":null,
  "modelCallId":"mc_xxx"
}
```

---

## 6.3 Workbench 展示模式

默认简化模式：

```text
✓ 已理解问题
✓ 已定位目标专利
✓ 已检索相关知识
✓ 已筛选 3 条证据
✓ 回答校验通过
```

展开“查看处理过程”：

```text
Route             SCOPED_RAG
目标专利           202311042981.1
目标文档           documentId=66
BM25 Candidates    8
Vector Candidates  10
Fusion             12
Rerank             3
Evidence            3
Generate Calls      1
Verify Calls        1
Repair Calls        0
Total               8.21s
```

对普通业务用户使用友好名称；工程字段可以在“高级详情”中展示。

---

## 6.4 Trace 与 Stage Event 必须同源

禁止分别维护：

```text
Workbench Thinking 数据源 A
Query Trace 数据源 B
```

正确方案：

```text
Pipeline emits StageEvent
        │
        ├── SSE → Workbench
        │
        └── TraceSink → Query Trace Store
```

确保聊天页和运行观测页看到的是同一个真实执行过程。

---

# 7. P0-C：Evidence / Citation 与流式输出联动

最终答案中 `[C1]` 必须稳定映射到 `evidence[0]`。

统一 Evidence 至少包含：

```text
evidenceId
chunkId
documentId
documentName
versionId
versionNo
kbId
domainCode
sectionType
sectionTitle
claimNo
pageStart
pageEnd
applicationNo
publicationNo
content
score
```

增加 Citation Validator：

- Answer 中所有 `[Cn]` 都必须满足 `1 <= n <= evidence.size`；
- 一个事实引用的 Evidence 必须真实支持该事实；
- Verify/Repair 时不得打乱 Citation 与 Evidence 索引映射；
- 历史会话重新打开后 Citation/Evidence 必须仍可恢复；
- 历史回答应保留当时 `versionId` 与 Evidence Snapshot，不跟随最新版本“漂移”。

建议持久化表：

```text
ai_chat_message_evidence
```

核心字段：

```text
id
message_id
query_id
trace_id
evidence_index
citation_label
kb_id
document_id
version_id
chunk_id
content_snapshot
metadata_snapshot
create_time
```

---

# 8. P0-D：回答反馈（Useful / Not Useful）

## 8.1 UI

每条 AI Answer 下：

```text
👍 有用    👎 无用
```

点击 👍：

- 立即记录 `HELPFUL`；
- 支持再次点击取消或修改反馈；
- 不要求弹窗。

点击 👎：

弹出原因：

```text
回答错误
没有回答到问题
引用证据不对
信息不完整
知识已过期
回答太啰嗦
回答太慢
其他
```

并允许可选备注。

---

## 8.2 Feedback API

建议：

```text
POST /admin-api/chat/feedback
```

请求：

```json
{
  "messageId":3021,
  "rating":"NOT_HELPFUL",
  "reasonCode":"WRONG_EVIDENCE",
  "comment":"引用的第一页没有支撑这个结论"
}
```

支持更新：

```text
PUT /admin-api/chat/feedback/{messageId}
```

或保持单一 Upsert API。

必须从 message/query context 自动补齐，不信任客户端提交：

```text
conversationId
queryId
traceId
tenantId
userId
kbId
domainCode
route
intent
modelId
promptVersion
evidenceIds
documentIds
knowledgeVersion
latencyMs
```

---

## 8.3 数据模型

建议：

```text
ai_chat_feedback
```

字段：

```text
id
message_id             UNIQUE
conversation_id
query_id
trace_id

tenant_id
user_id
kb_id
domain_code

rating                 HELPFUL / NOT_HELPFUL
reason_code
comment

route
intent
confidence
latency_ms

model_id
prompt_version

primary_document_id
evidence_snapshot

create_time
update_time
```

如反馈事件需要历史审计，可增加：

```text
ai_chat_feedback_event
```

保存用户每次修改前后的状态。

---

# 9. P1：反馈学习闭环

## 9.1 原则

**禁止把一次 👍/👎 直接作为训练标签喂给模型。**

原因：

- 用户可能误点；
- 用户未必能判断事实真伪；
- 恶意反馈存在；
- 一个“有用”回答可能 Citation 错误；
- “无用”可能只是表达风格问题，不代表知识错误。

Feedback 首先是**质量信号**，不是直接训练标签。

---

## 9.2 负反馈闭环

```text
NOT_HELPFUL
   ↓
Failure Classification
   ├─ WRONG_ROUTE
   ├─ RETRIEVAL_MISS
   ├─ WRONG_EVIDENCE
   ├─ HALLUCINATION
   ├─ KNOWLEDGE_GAP
   ├─ OUTDATED_KNOWLEDGE
   ├─ TOO_VERBOSE
   └─ TOO_SLOW
   ↓
Bad Case Pool
   ↓
人工确认 / 自动规则确认
   ↓
Eval Case
   ↓
回归测试
```

Bad Case 需要保留当时：

```text
query
answer
route
evidence
knowledge version
prompt version
model version
trace
feedback
```

否则未来无法复现。

---

## 9.3 正反馈闭环

```text
HELPFUL
   ↓
Query Clustering
   ↓
High-frequency + High-positive-rate Cluster
   ↓
Stable Evidence Check
   ↓
FAQ Candidate
   ↓
Human Review
   ↓
Published FAQ
```

FAQ Candidate 推荐条件（初始建议，可配置）：

```text
30天 queryCount >= 30
positiveRate >= 90%
negativeRate <= 5%
Evidence Stability >= 90%
No active knowledge conflict
Source knowledge currently effective
```

这只是候选，不自动发布。

---

# 10. FAQ Candidate 与 FAQ Fast Path

## 10.1 FAQ Candidate

建议表：

```text
ai_faq_candidate
```

字段：

```text
id
kb_id
domain_code
cluster_key
canonical_question
suggested_answer
source_query_count
positive_rate
negative_rate
source_document_ids
source_version_ids
evidence_snapshot
status
reviewer_id
review_time
create_time
```

状态：

```text
CANDIDATE
APPROVED
REJECTED
NEED_REVALIDATION
PUBLISHED
```

---

## 10.2 FAQ 正式知识

审批通过后成为正式知识对象，不应该只是“模型记住了”。

至少关联：

```text
kbId
domainCode
sourceDocumentId/sourceVersionId
evidenceIds
effectiveFrom/effectiveTo
reviewStatus
```

源知识新版本发布时：

```text
FAQ → NEED_REVALIDATION
```

重新验证通过才继续走 Fast Path。

---

## 10.3 Query Planner Fast Path

未来 Route 优先级建议：

```text
RULE
  ↓
EXACT_METADATA / EXACT_CLAIM
  ↓
APPROVED_FAQ
  ↓
SCOPED_RAG
  ↓
HYBRID_RAG
  ↓
ABSTAIN
```

FAQ Fast Path 必须满足：

- semantic/exact match 达阈值；
- FAQ 当前有效；
- 绑定 Source Version 仍有效；
- 无冲突；
- ACL/tenant 允许；
- Evidence 可返回。

命中后可直接返回稳定 Answer + Citation，不必重复完整 Hybrid RAG。

收益：

- 高频问题响应显著下降；
- 模型成本下降；
- 答案一致性提升；
- 企业知识运营人员可以明确审核和维护。

---

# 11. 反馈指标体系

P1 建议形成以下 Dashboard：

```text
总问答数
Helpful Rate
Not Helpful Rate
Abstain Rate
Average / P95 Latency
Citation Error Rate
Knowledge Gap Rate
Route Error Rate
Repair Rate
FAQ Hit Rate
```

支持维度：

```text
KB
Domain
Route
Intent
Model
Prompt Version
Document
Time
User/Role（需遵循权限）
```

重点榜单：

```text
TOP 高频问题
TOP 点踩问题
TOP 无答案问题
TOP 引用错误问题
TOP 知识缺口
TOP 慢查询
TOP 高 Repair 查询
从未命中的知识
高频 Evidence
```

---

# 12. 隐私、安全与审计

1. Stage Event 不输出隐藏推理链。
2. 不记录 API Key、Token、Authorization Header。
3. Prompt 可记录 `promptVersion`，不必在普通 Trace 暴露完整 Prompt。
4. Feedback Comment 视作用户输入，需要租户隔离、内容审计和最小权限访问。
5. FAQ Candidate 生成不能突破原 KB ACL。
6. 从反馈生成 Eval/FAQ 时必须保留 tenant/kb/domain 边界。
7. 删除用户/会话数据时，应考虑 Feedback/Evidence Snapshot 的数据生命周期策略。

---

# 13. 本轮任务拆分

## P0-08A Streaming Backend

- 新增 `/chat/stream` SSE；
- 保留 `/chat/send`；
- 定义统一 `ChatStreamEvent`；
- Pipeline 支持 EventSink；
- `delta` 输出；
- `done` 最终权威结果；
- Cancellation；
- Query Deadline；
- in-flight/idempotency 防重复。

## P0-08B Stage Event / Trace 同源

- 定义 `QueryStageEvent`；
- Pipeline 每阶段 emit；
- SSE 消费；
- Trace Sink 持久化；
- 统一 Stage Enum；
- SKIPPED/FAILED/DEGRADED 状态。

## P0-09 Evidence/Citation

- Evidence DTO 统一；
- Citation Validator；
- Message Evidence 持久化；
- 历史会话恢复 Evidence/Citation/Trace；
- Evidence Drawer。

## P0-10A Workbench Streaming

- SSE 客户端；
- 增量 Answer；
- Stop Generation；
- Loading/Retry；
- 重连/异常结束处理；
- 防重复发送。

## P0-10B Thinking UI

- 默认简化 Stage；
- 展开详细过程；
- Stage 实时状态；
- 耗时；
- 与 Trace Deep Link。

## P0-10C Feedback UI

- Helpful/Not Helpful；
- 点踩原因；
- Optional Comment；
- 已反馈状态恢复；
- 允许修改。

## P0-11A Feedback Backend

- `ai_chat_feedback`；
- Upsert API；
- 权限/租户校验；
- 自动关联 Query/Trace/Model/Prompt/Evidence；
- 基础统计 API（P0 可只提供计数和 rate）。

## P0-11B Business Error

- Streaming error event；
- Query timeout；
- Model unavailable；
- Retrieval unavailable；
- Knowledge/Conversation errors；
- 前端友好提示。

---

# 14. P0 验收标准

## Streaming

- 连接建立后 500ms 内至少收到 `conversation` 或首个 `stage`（本地正常环境）；
- Answer 生成时持续收到 `delta`；
- 最终收到唯一 `done` 或 `error`；
- 用户点击停止后不再继续输出 Answer；
- 双击发送不生成两个 Query。

## Thinking / Stage

- SCOPED_RAG 能看到 ANALYZE / SCOPE / RETRIEVE / RERANK / EVIDENCE / GENERATE / VERIFY；
- EXACT_METADATA 明确显示 Vector/Generate 为 SKIPPED；
- 页面展示内容与 Query Trace 一致；
- 不出现隐藏 CoT/Prompt/Token。

## Evidence / Citation

- `[Cn]` 无越界；
- Citation 与 Evidence 一一对应；
- F5 后仍存在；
- 历史 Conversation 重开仍存在；
- 旧回答保留原 version/evidence snapshot。

## Feedback

- 每条 AI Message 只能有一个当前 Feedback；
- 支持修改；
- 非 Message Owner 不能提交；
- Feedback 自动关联 KB/Domain/Route/Trace；
- 点踩原因可统计。

## Workbench

- 白天/夜间模式正常；
- 输入不中断；
- 流式期间 UI 不冻结；
- Evidence Drawer 正常；
- Thinking 可折叠；
- Feedback 正常；
- Trace 一键跳转。

---

# 15. P1 任务：反馈学习与 FAQ 沉淀

P0 完成后执行：

```text
P1-01 Feedback Analytics
P1-02 Bad Case Mining
P1-03 Feedback → Eval Case
P1-04 Query Embedding / Clustering
P1-05 High-frequency Question Detection
P1-06 FAQ Candidate Generation
P1-07 Human Review Workflow
P1-08 FAQ Publish / Revalidation
P1-09 FAQ Fast Path
P1-10 Feedback-driven Prompt/Retrieval Optimization
```

注意：

- 不直接拿点赞/点踩做模型训练；
- 优先优化知识、Route、Retriever、Reranker、Prompt；
- 真正积累足够人工确认的高质量数据后，再评估 SFT / Preference Tuning。

---

# 16. DeepSeek / Codex 直接执行任务书

```text
任务名称：AI-Knowledge 对话体验与反馈闭环 P0

当前目标：
将知识问答工作台升级为：
Streaming Chat + 可解释执行过程 + Evidence/Citation + Feedback。

本轮执行顺序严格如下：

1. P0-08A Streaming Backend
2. P0-08B Stage Event / Trace 同源
3. P0-09 Evidence/Citation 持久化
4. P0-10A Workbench Streaming
5. P0-10B Thinking UI
6. P0-10C Feedback UI
7. P0-11A Feedback Backend
8. P0-11B Streaming/Business Error
9. P0 Final Regression

禁止：
- 新 Domain
- Agent
- Patent Copilot
- Prior Art
- FAQ 自动发布
- 用户反馈直接训练模型
- 大规模重新设计现有 UI
- 输出隐藏模型思维链

后端重点：
- SSE /chat/stream
- ChatStreamEvent
- QueryStageEvent
- EventSink + TraceSink
- Cancellation
- Query Deadline
- Citation Validator
- ai_chat_message_evidence
- ai_chat_feedback
- Feedback Upsert API

前端重点：
- SSE 消费
- delta 流式渲染
- Stop Generation
- Stage/Thinking Panel
- Evidence Drawer
- Helpful / Not Helpful
- 点踩原因
- Trace Deep Link

最终必须验证：
A. EXACT_METADATA 流式/Stage
B. EXACT_CLAIM 流式/Stage
C. SCOPED_RAG 流式/Stage
D. HYBRID_RAG 流式/Stage
E. ABSTAIN
F. Stop Generation
G. F5 后 Evidence/Citation/Feedback 恢复
H. 20轮连续问答
I. 暗色/亮色模式
J. Feedback 用户隔离

完成后停止，不自行进入 P1。

报告输出：
Backend HEAD
Frontend HEAD
Migration
Changed Files
Maven Test
Frontend Build
SSE Event Sample
Stage Sample
Evidence Persistence Result
Feedback API Result
20-turn Result
Known P1 Debt
```

---

# 17. 推荐提交粒度

```text
feat(chat): add streaming conversation endpoint
feat(trace): emit query stage events to stream and trace
feat(evidence): persist message citation evidence
feat(workbench): render streaming answers and query stages
feat(feedback): collect answer usefulness feedback
fix(chat): unify streaming error and cancellation handling
test(chat): cover streaming evidence feedback lifecycle
```

不要把整个改造压成一个巨型 Commit。

---

# 18. 最终阶段判断

完成本方案 P0 后，系统应达到：

> **Patent AI Knowledge Base Beta / Controlled Pilot Candidate**

用户侧已经具备：

```text
问问题
→ 实时看到系统执行
→ 流式获得回答
→ 查看 Evidence / Citation
→ 查看完整 Trace
→ 对结果反馈
```

平台侧已经具备：

```text
Query
→ Trace
→ Answer
→ Evidence
→ Feedback
```

完成 P1 后进一步形成：

```text
真实使用
→ 质量反馈
→ Bad Case / 高频问题
→ Eval / FAQ Candidate
→ 人工审核
→ Fast Path
→ 持续监控
```

这时平台才真正具备“越用越准、越用越快、知识持续沉淀”的知识运营能力。
