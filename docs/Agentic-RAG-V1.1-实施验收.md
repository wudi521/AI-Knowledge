# Agentic RAG V1.1 实施验收基线

> 对应架构：`docs/Agentic-RAG-V1.1-架构设计.md`  
> Structured Core 封板：`docs/Agentic-RAG-V1.1-Structured-Core-封板.md`  
> 分支：`refactor/knowledge-platform-business-p0`  
> 状态：功能实现与结构化内核收口完成，进入编译/真实数据回归/灰度验证  
> 更新日期：2026-08-25

## 1. 当前结论

V1.1 已完成代码层主架构与 Structured Core 第二轮收口。当前不再通过新增业务 Intent、中文关键词 if 或问句专用 capability 解决新问法。

已经落地：

- immutable `originalGoal`；
- `CALL_CAPABILITY / ANSWER / NEED_MORE_INFO / STOP` 四种机器动作；
- 有界 Agent 循环、重复调用/无进展/时间预算；
- Capability Registry / Visibility Policy / Invoker；
- 系统 tenant/user/kb/domain scope 保护；
- 强制 capability timeout / maxRows；
- capability 参数名白名单 + 机器参数 validator；
- 可修复 capability contract 错误最多 2 次受控重规划；
- 权限/系统 scope/数据完整性错误不可绕过；
- BM25 + Vector + Fusion + Rerank 整体封装为 `knowledge_retrieval`；
- 组合式 `structured_query`；
- `exact_text_search`；
- `similar_field_values`；
- `patent_claim_lookup`；
- trusted entity scope + candidate contamination 防线；
- Chat / Eval / Admin 统一 Router；
- `V3 / AGENT / AGENT_WITH_V3_FALLBACK`；
- Agent 参数级 Trace、持久化与 traceId 回放；
- 确定性 STRUCTURED_RESULT provenance；
- `confidence=null` 的未校准语义贯穿 API / Java / DB 契约；
- Schema 自动契约测试与 V1.1 专属 CI。

V3 目前仅作为迁移期兼容执行器保留。

## 2. Agent 核心

关键目录：

```text
yudao-module-evidence/yudao-module-evidence-server/src/main/java/
cn/iocoder/yudao/module/evidence/service/agent/
```

关键类：

- `AgentExecutionState`：`originalGoal` 构造后不可修改；
- `AgentExecutionGuard`：maxSteps / maxLlmCalls / maxElapsedMs / repeated-call / no-progress；
- `LlmAgentPlanner`：读取 capabilities + Domain Schema + typed observations；
- `AgenticQueryEngine`：统一执行循环；
- `AgentObservation`：携带 completeDataset / authoritativeEmpty / recoverableError / metadata；
- `AgentTraceStep`：记录 action / capability / argumentsSummary / purpose / status / elapsed / stopReason。

Planner 使用闭世界结构化语义：如果用户概念没有已注册字段/指标/安全变换，或存在会改变含义的多种映射，必须澄清/停止，不允许偷偷替换成相近字段。

例如 PATENT 目前有 `申请日`、`公开日`，没有 `发明时间`。因此“哪件专利发明时间最早”不应静默等价为申请日。

## 3. Capability 治理

统一入口：`CapabilityInvoker`。

执行前依次校验：

1. capability 是否存在且当前可见；
2. 禁止 Planner 设置 tenantId/userId/kbId/domainCode/permissions/contextEntityIds 等系统范围；
3. 参数名必须在 capability schema 白名单；
4. required 参数；
5. capability 机器参数 validator：类型、JSON 形状、范围；
6. 生成与系统 scope 绑定的稳定 fingerprint；
7. timeout；
8. maxRows。

参数名/缺参/类型/范围等纯调用契约错误可以进入最多 2 次受控自修复；权限、系统 scope、timeout、真实数据不完整/冲突不可修复。

## 4. `structured_query` V2：组合式 Structured Pipeline

线上 Spring Runtime 强制依赖新 `StructuredPipelineCapabilityDelegate`，不允许因 Bean 缺失静默退回旧 V3 Structured Executor。

统一执行模型：

```text
完整逻辑实体集合
  -> 读取已注册字段/指标
  -> 多值展开（可选）
  -> 安全派生值（可选）
  -> AND/OR 类型化过滤（可选）
  -> DISTINCT（可选）
  -> GROUP BY（可选）
  -> COUNT / COUNT_DISTINCT / SUM / AVG / MIN / MAX（可选）
  -> 字段 / 派生值 / Metric / 聚合值排序（可选）
  -> LIMIT
  -> 投影
```

Pipeline 参数：

```text
select
filter
groupBy
aggregate
orderBy
distinct
limit
```

迁移期仍解析旧 `task/field/projections/metric/...` 参数，但只是兼容输入，线上最终执行仍编译为 Pipeline。

### 4.1 安全派生值

当前物理变换：

- `LENGTH`
- `YEAR`
- `MONTH`
- `YEAR_MONTH`
- `VALUE_COUNT`
- `PERSON_SURNAME`

字段只有在 Domain Schema 的 `allowedTransforms` 声明后才能使用。

例：

```text
最早申请
FILING_DATE -> ORDER ASC -> LIMIT 1

标题最长
TITLE -> LENGTH -> ORDER DESC -> LIMIT 1

不同发明人姓氏数量
INVENTOR -> EXPLODE -> PERSON_SURNAME -> COUNT_DISTINCT

每个发明人参与多少专利
INVENTOR -> EXPLODE -> GROUP -> COUNT

每年申请多少件
FILING_DATE -> YEAR -> GROUP -> COUNT
```

这些是有限的物理执行能力，不是用户 Intent 枚举。

### 4.2 多值字段

`multiValue=true` 已成为真实执行语义：

- `explode=false`：按规范化集合值处理；
- `explode=true`：按单个元素过滤/分组/去重/聚合/投影；
- `VALUE_COUNT`：直接得到元素个数；
- 多值常见分隔符统一处理；
- 重复逻辑实体的多值字段按集合比较，顺序/分隔符变化不制造假冲突；
- 任一元素安全变换失败，整个实体派生值视为缺失，防止静默少算；
- exploded value rows 不携带 trusted entity id。

### 4.3 类型与完整性

字段 `DATE / INTEGER / DECIMAL / STRING` 由 Schema 决定，执行器不再“猜类型”。

- 非法日期/数字源值视为缺失；
- 非法过滤 literal 是可修复 Planner contract error；
- typed compare 失败绝不退化成字符串比较；
- 显式投影、过滤、聚合、排序、分组所需字段缺值时 fail-closed；
- V1.1 当前 `PARTIAL` 默认不可作答；
- group/projection 笛卡尔展开均有硬预算。

### 4.4 逻辑专利实体与重复数据

PATENT 按：

1. 申请号；
2. 公布号；
3. documentId 兜底

形成逻辑实体。

重复导入不会让“专利数/标题集合/字段关系”使用不同口径。

重复记录只有在当前查询真正依赖的字段/Metric 上冲突时才阻断；禁止 `putIfAbsent` 静默选第一份。

## 5. PATENT 当前真实 Schema

### Metrics

真实注册并可执行：

- `PATENT_COUNT`
- `DOCUMENT_COUNT`
- `CLAIM_COUNT`

独立/从属权利要求数在底层真实数据提取完成前不注册给 Planner。

### Fields

- `PUBLICATION_NO`
- `APPLICATION_NO`
- `TITLE`
- `APPLICANT`（multiValue）
- `INVENTOR`（multiValue）
- `FILING_DATE`
- `PUBLICATION_DATE`

Planner 可见：valueType / multiValue / filterable / operators / sortable / groupable / allowedTransforms / exactIdentifier。

## 6. 其他能力

### `knowledge_retrieval`

复用：

```text
BM25 + Vector -> Fusion -> Rerank -> Evidence
```

Planner 不控制底层检索算法，语义候选不能自动进入 trusted scope。

### `exact_text_search`

只处理明确逐字原文包含/出现/精确短语要求。

### `similar_field_values`

在完整结构化文本字段集合上做通用相似关系；当前在线 pairwise 上限 2000，超过上限必须使用索引化实现，不允许 TopK 冒充全集。

### `patent_claim_lookup`

只在唯一 trusted patent entity 上做权利要求原文/依赖关系确定性查询。

## 7. Trusted Scope

允许来源：

1. 服务端解析的 conversation context；
2. 确定性实体型 capability 输出。

禁止来源：

- semantic retrieval candidate；
- COUNT / AGGREGATE / GROUP；
- exploded value projection；
- 输出行数与 verified IDs 不一致的结果。

## 8. Trace / Replay

Agent stages：

- `AGENT_PLANNER`
- `AGENT_CAPABILITY_PREPARE`
- `AGENT_CAPABILITY`
- `AGENT_TRUSTED_SCOPE`
- `AGENT_GUARD`
- `AGENT_ANSWER`
- `AGENT_STOP`

Trace 现在包含经过截断/安全处理的 capability `arguments` 摘要，因此可以直接审计 Planner 实际构造了什么 Pipeline。

持久化表：

```text
ai_query_trace_stage
```

回放接口：

```text
GET /evidence/agent-trace/{traceId}
```

## 9. Confidence / Evidence

Agent V1.1 连续 confidence 尚未离线校准：

```text
confidence = null
```

会话级 `ai_evidence_eval.confidence` 必须允许 NULL，表示 unknown / uncalibrated；不能写成 0。

chunk evidence score 仍遵守 `ai_evidence.confidence NOT NULL`，两者不是同一种指标。

确定性答案没有 CHUNK 时，响应仍补 `STRUCTURED_RESULT` provenance。

## 10. 数据库迁移

本轮测试前确保执行：

```text
sql/migrate-20260825-agent-v11-trace.sql
sql/migrate-20260825-agent-v11-confidence-null.sql
```

第一条提供 Agent stage 持久化；第二条保证未校准 confidence 能以 NULL 保存。

## 11. 自动契约测试

CI：

```text
.github/workflows/agent-v11-ci.yml
```

重点不变量：

- every registered field/metric has adapter support；
- every `sortable=true` field can execute ASC/DESC；
- every `groupable=true` field can GROUP；
- every allowed operator executes；
- every allowed transform validates/executes；
- every supported metric operation executes；
- field date Top-N；
- LENGTH ordering；
- VALUE_COUNT ordering；
- YEAR grouping；
- multi-value explode/group/count/distinct；
- PERSON_SURNAME distinct count；
- explicit exploded value projection；
- typed invalid literal classification；
- missing source/projection fail-closed；
- duplicate logical entity conflict；
- recoverable prepare/execution errors；
- protected scope cannot self-repair；
- candidate/trusted-scope contamination；
- trace replay；
- eval confidence null persistence semantics。

## 12. 当前明确边界

- Agent 原生仍是单 KB；多 KB 在迁移模式走受控 V3 fallback，纯 Agent fail-closed。
- `similar_field_values` >2000 实体需要索引化 nearest-neighbor 物理能力。
- `PERSON_SURNAME` 是受控姓名解析能力，不是任意语言姓名推理；不能可靠解析时必须 fail-closed。
- V3 仍保留用于真实回归对照，未达到灰度门槛前不删除。

## 13. Definition of Done

代码层：

- [x] Agent 主循环 / immutable goal
- [x] capability registry / visibility / invoker
- [x] system scope protection
- [x] timeout / maxRows / budgets
- [x] machine argument validation
- [x] bounded self-repair
- [x] typed observations / authoritative empty
- [x] retrieval / exact text / claim / similarity capabilities
- [x] compositional Structured Pipeline
- [x] typed fields / filters / transforms / grouping / aggregate / ordering / distinct / explode
- [x] logical entity merge / conflict / completeness guards
- [x] trusted-scope guards
- [x] arguments Trace + persistence + replay
- [x] confidence unknown semantics + DB migration
- [x] Schema-driven contract tests
- [x] dedicated V1.1 CI workflow checked into repository

运行验收：

- [ ] GitHub Actions / 本地 Maven 实际编译与测试跑绿
- [ ] 真实专利黑盒测试通过
- [ ] V3 vs Agent 指标对照完成
- [ ] 达到门槛后切纯 `AGENT`
- [ ] 稳定观察后移除 V3 顶层主脑

在运行验收完成前，不能把“代码已实现”表述为“生产已验证”。
