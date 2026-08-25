# Agentic RAG V1.1 实施验收基线

> 对应架构文档：`docs/Agentic-RAG-V1.1-架构设计.md`  
> 分支：`refactor/knowledge-platform-business-p0`  
> 状态：V1.1 功能实现完成，进入回归/灰度验证阶段  
> 更新日期：2026-08-25

## 1. 结论

V1.1 架构文档定义的第一阶段能力已经完成代码落地：

- 原始目标只读。
- 四种机器动作协议。
- 有界 Agent 执行循环。
- Capability Registry / Visibility Policy / Invoker。
- 参数白名单、系统范围保护、重复调用保护、无进展保护。
- 强制 capability timeout 与 maxRows。
- 现有混合检索整体封装为 capability。
- 通用结构化 capability。
- Exact Text capability。
- 通用文本字段集合相似 capability。
- PATENT 精确权利要求 capability。
- trusted entity scope 的建立与跨步骤传播。
- Chat / Evidence RPC / 管理端统一查询 Router。
- V3 / AGENT / AGENT_WITH_V3_FALLBACK 三种迁移模式。
- Agent 逐步骤 Trace。
- Trace stage 持久化与 traceId 事后回放。
- 确定性答案 Structured provenance。
- V1.1 独立 JDK17 CI 与架构契约测试矩阵。

V3 当前作为迁移期兼容执行器保留，不再是唯一查询主脑。

## 2. 核心实现映射

### 2.1 Agent 核心

目录：

```text
yudao-module-evidence/yudao-module-evidence-server/src/main/java/
cn/iocoder/yudao/module/evidence/service/agent/
```

关键类：

- `AgentExecutionState`：保存 immutable `originalGoal`。
- `AgentActionType`：`CALL_CAPABILITY / ANSWER / NEED_MORE_INFO / STOP`。
- `AgentExecutionGuard`：步骤数、LLM 次数、时间预算、重复调用、无进展保护。
- `LlmAgentPlanner`：只决定下一步动作与 capability，不决定底层检索实现。
- `AgenticQueryEngine`：统一有界执行循环。
- `AgentTraceStep`：逐步骤可审计 Trace。

### 2.2 Capability 基础设施

目录：

```text
service/agent/capability/
```

关键类：

- `CapabilityDefinition`
- `CapabilityInvocationContext`
- `CapabilityRegistry`
- `DefaultCapabilityVisibilityPolicy`
- `CapabilityInvoker`
- `AgentCapabilityOutput`

已落实：

- 系统范围由服务端 context 注入。
- Planner arguments 不允许覆盖 tenantId/userId/kbId/domainCode/traceId/permissions/environment。
- capability 定义包含参数 schema、required args、output type、domain、permission、KB capability、timeout、maxRows。
- Invoker 实际使用 `Future#get(timeout)` 实施强制 timeout，而不是只记录配置。
- 输出行数超过 maxRows 时 fail-closed。

## 3. 已实现能力

### 3.1 `knowledge_retrieval`

复用现有完整检索链：

```text
BM25 + Vector -> Fusion -> Rerank -> Evidence
```

Planner 只看到一个知识检索能力，不接触 BM25/Milvus/RRF 等内部算法。

语义检索结果不能直接成为 trusted entity scope。

### 3.2 `structured_query`

Domain Registry 驱动的通用结构化能力，支持：

- PROJECT
- LIST
- COUNT
- AGGREGATE
- TOP_N
- 注册字段过滤
- 白名单 FilterOperator
- 多字段投影

可覆盖专利领域已有的：

- 申请号 / 公布号精确定位
- 标题 / 申请人 / 发明人过滤
- 字段投影
- 专利数量
- 权利要求数量聚合
- Top-N 等

结构化结果返回 `verifiedEntityIds`，只有这些确定性实体才允许升级为 trusted scope。

### 3.3 `exact_text_search`

处理用户明确要求“原文逐字包含 / 原文出现”的查询，避免把 Exact Text 和普通语义相关性混为一谈。

### 3.4 `similar_field_values`

通用集合级文本字段相似能力，不硬编码“专利名称相近” Intent。

例如 PATENT Domain 中：

```text
field = TITLE / 标题 / 专利名称 / 发明名称
```

能力在完整结构化字段集合上进行相似计算。

硬约束：

- 数据源截断时不能给全集结论。
- 任一实体目标字段缺失时不能给全集否定结论。
- 当前在线 pairwise 上限为 2000 个实体；超过上限返回 `CAPABILITY_UNAVAILABLE`，禁止 TopK 冒充全集。
- 当前算法为字符二元组 Dice，可后续替换为索引化实现而不改变 Planner/Agent 协议。

### 3.5 `patent_claim_lookup`

PATENT 精确权利要求能力：

- RAW：指定 claim 原文。
- DEPENDENCY：指定 claim 引用/从属依赖。

必须先存在且仅存在一个 trusted patent entity，禁止普通语义候选直接成为 claim 查询对象。

最终使用 sectionType=CLAIMS + claimNo 元数据唯一确认，不能依靠 RAG 猜测权利要求依赖关系。

## 4. 候选污染防线

迁移期间旧 V3 的 `RetrievalRefinementService` 也增加了候选污染保护。

以下链路被禁止：

```text
用户问集合关系
-> 第一轮偶然命中对象 A
-> 把 A 的标题当成用户指定事实
-> 第二轮围绕 A 搜索
-> 高置信回答另一个问题
```

候选实体的新事实只能作为 Observation，不能回写用户原始目标或硬查询锚点。

## 5. Trusted Scope

trusted entity 只来自：

1. 服务端已解析的 conversation context entity ids；
2. 确定性 capability 返回的 `verifiedEntityIds`。

普通 `knowledge_retrieval` 候选不允许进入 trusted scope。

同一次 Agent 请求中，trusted scope 可以由确定性能力增量扩展；tenant/user/kb/domain 等系统范围保持不可变。

## 6. 顶层迁移 Router

统一入口：`EvidenceQueryRouter`。

支持模式：

```text
V3
AGENT
AGENT_WITH_V3_FALLBACK
```

Chat RPC、Eval Runner、管理端评估统一经过该 Router，不再分别维护迁移逻辑。

本地配置当前采用：

```yaml
yudao:
  evidence:
    agent:
      mode: AGENT_WITH_V3_FALLBACK
```

### 安全回退原则

只有迁移/基础设施类错误允许回退 V3，例如：

- CAPABILITY_UNAVAILABLE
- MAX_STEPS
- MAX_LLM_CALLS
- TIME_BUDGET_EXCEEDED
- REPEATED_CALL
- NO_PROGRESS
- INVALID_CAPABILITY_CALL
- AGENT_SINGLE_KB_REQUIRED

以下情况禁止由 V3 推翻 Agent 的保守结论：

- `NO_RELIABLE_EVIDENCE`
- `NEED_USER_INPUT`
- `PERMISSION_DENIED`

因此不会出现“Agent 判断证据不足，V3 又强行猜一个答案”的迁移倒退。

## 7. Evidence / Answerable

V1.1 不再输出未经校准的连续 confidence，Agent 返回 `confidence=null`。

确定性结构化回答即使没有 CHUNK，也会在响应中产生：

```text
evidenceType = STRUCTURED_RESULT
```

用于表明答案来自确定性结构化执行，而不是 LLM 自由生成。

语义回答仍必须经过现有 AnswerPipeline / Claim Verification。

## 8. Trace 与回放

Agent 每步都映射回现有 `stages`：

- PLANNER
- CAPABILITY_PREPARE
- CAPABILITY
- TRUSTED_SCOPE
- GUARD
- ANSWER
- STOP

每步包含：

- action
- capability
- purpose
- status
- elapsedMs
- summary
- stopReason

迁移回退时额外记录：

```text
AGENT_FALLBACK_TO_V3
```

并沿用同一 traceId。

V1.1 现在不仅把 stages 返回给调用方，还持久化到：

```text
ai_query_trace_stage
```

`EvidenceRecorder` 对 Agent/V3 的最终 stages 统一执行 replace 持久化；同一 traceId 重写时先删除旧步骤，避免重复/脏回放。Agent→V3 fallback 在合并两段步骤后再次 replace，因此事后看到的是完整执行链而不是单独 V3 片段。

管理端只读回放接口：

```text
GET /evidence/agent-trace/{traceId}
```

返回按 `seq` 排序的 `QueryStageTimingDTO`。Trace 存储 fail-open：审计写入异常不会改变查询答案，但会记录 warn 日志。

## 9. 自动化回归

新增工作流：

```text
.github/workflows/agent-v11-ci.yml
```

固定 JDK 17，执行：

1. evidence-server 及依赖模块编译；
2. V1.1 专属契约测试。

当前 V1.1 回归覆盖：

- immutable originalGoal
- maxSteps / maxLlmCalls / time budget
- 重复调用 / no progress
- 系统范围参数保护
- capability timeout
- maxRows
- capability visibility
- trusted entity scope
- candidate feedback contamination
- generic structured query
- collection similarity + missing field fail-closed
- exact patent claim lookup
- deterministic + semantic composite flow
- V1.1 -> V3 controlled fallback
- fallback 合并后的完整 trace 持久化刷新
- traceId stage replace / replay DTO 重建
- NEED_USER_INPUT 不回退
- PERMISSION_DENIED 不回退
- deterministic answer structured provenance
- V3 bypass mode
- single-KB pure Agent fail-closed boundary

## 10. 当前刻意保留的迁移边界

以下不是 V1.1 架构缺失，而是明确的迁移/规模边界：

### 10.1 V3 仍保留

V1.1 文档原本就要求迁移期保留旧链路用于回归和对照。

在 V1.1 回归稳定前，不删除 V3。

### 10.2 Agent 当前单 KB 原生执行

Agent 原生 capability context 当前使用一个权威 kbId。

多 KB 请求在 `AGENT_WITH_V3_FALLBACK` 模式下回退现有 V3；纯 `AGENT` 模式 fail-closed。

如果产品后续要求 V1.1 原生跨多 KB，应把 `CapabilityInvocationContext.kbId` 升级为系统管理的 scope 对象，而不是允许 Planner 自己传 kbIds。

### 10.3 大规模集合相似

当前 `similar_field_values` 在线算法用于小/中规模知识库验证正确性，上限 2000。

更大规模应增加索引化 nearest-neighbor capability，实现层可以替换，但 Agent 协议、Planner、Evidence 契约不需要改变。

## 11. V1.1 Definition of Done

以下条件同时满足才可把环境切为纯 `AGENT`：

- [x] 架构主循环完成
- [x] 原始目标只读
- [x] capability registry / invoker / governance 完成
- [x] 强制 timeout / maxRows 完成
- [x] retrieval capability 完成
- [x] structured capability 完成
- [x] exact text capability 完成
- [x] patent claim capability 完成
- [x] collection similarity capability 完成
- [x] trusted scope 完成
- [x] candidate feedback contamination 防线完成
- [x] Chat / Eval / Admin 统一 Router 完成
- [x] 逐步骤 Trace 完成
- [x] Trace stage 持久化与 traceId 回放完成
- [x] V3 安全回退策略完成
- [x] V1.1 独立 CI 已加入仓库
- [x] capability / architecture contract tests 已加入仓库
- [ ] CI 在实际 GitHub Actions 环境跑绿
- [ ] 使用真实专利测试集完成 V3 vs Agent 回归对照

最后两项是运行验收，不是缺失代码功能；通过后即可将目标环境从 `AGENT_WITH_V3_FALLBACK` 切为 `AGENT`。
