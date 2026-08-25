# Agentic RAG V1.1 架构设计

> 状态：开发基线（2026-08-25）  
> 适用分支：`refactor/knowledge-platform-business-p0`

## 1. 背景与目标

当前 V3 已具备知识库、权限、领域意图、结构化查询、全文/向量检索、融合、重排、证据与 Trace 等基础能力，但问题入口仍然容易受固定 Intent、查询改写和检索候选影响。典型故障是：用户原始目标是“判断库中是否存在名称相近的专利”，第一次检索命中某个专利后，后续轮次把候选专利名称升级成了新的查询目标，导致系统回答了另一个问题。

V1.1 的目标不是重写现有 RAG，而是把最上层的“谁决定下一步怎么做”改造成受控的 Agentic 执行循环，同时保留现有成熟检索链。

核心目标：

1. 用户原始目标在一次请求生命周期内不可变。
2. 不再通过持续新增业务 Intent 来覆盖未知问法。
3. 模型只决定“下一步使用哪个系统能力”，不能决定租户、知识库、权限等系统边界。
4. 现有 BM25 / Vector / Fusion / Rerank 继续作为一个完整检索能力的内部实现，不暴露给规划模型。
5. 每一步都有限预算、可追踪、可停止、可回放。
6. `answerable` 由证据覆盖和停止原因驱动，不先制造未经校准的伪精确 confidence。

## 2. 非目标

V1.1 第一阶段明确不做：

- 不建设通用自治 Agent 平台。
- 不引入 Semantic Kernel 等第二套模型调用基础设施，仅借鉴其函数调用架构思想。
- 不让模型直接执行任意 Java/Python/SQL 代码。
- 不把 BM25、向量、融合、重排拆成独立工具交给模型自由组合。
- 不为“名称相近、统计、趋势、比较”等每一种用户语义增加固定 Intent。
- 不通过增加模型调用次数来堆叠“观察器/完成判断器/核验器”。

## 3. 不可违反的架构约束

### 3.1 原始目标只读

一次请求创建后：

```text
originalGoal = 用户本轮真实问题（只读）
currentSubGoal = 当前执行子目标（可变）
```

`originalGoal` 创建后禁止 setter、禁止重写、禁止由检索结果回填。检索候选只能进入 `observations/evidence`，绝不能覆盖原始问题。

### 3.2 规划器只有四种机器动作

规划模型的输出必须收敛到有限协议：

```text
CALL_CAPABILITY  调用一个能力
ANSWER           已有足够证据，生成回答
NEED_MORE_INFO   缺少用户必须补充的信息
STOP             当前条件下不能继续
```

固定的是机器动作，不是用户语义分类。系统仍然可以处理从未见过的新问法。

建议结构：

```json
{
  "action": "CALL_CAPABILITY",
  "capability": "knowledge_retrieval",
  "arguments": {
    "query": "..."
  },
  "purpose": "为了回答原始目标，本步需要获得..."
}
```

### 3.3 系统范围不属于模型参数

以下数据由服务端 `CapabilityInvocationContext` 注入，模型无权指定或覆盖：

- tenantId
- userId
- kbId / 当前知识库范围
- 权限集合
- 数据可见范围
- requestId / traceId
- 超时与预算

任何模型返回中出现这些系统字段，都必须忽略或拒绝，不能以模型值执行。

### 3.4 能力边界原则

> 模型需要决定“什么时候用”，但不需要决定“内部怎么实现”的东西，才应该成为能力。

因此现有检索链整体包装为一个 `knowledge_retrieval` 能力：

```text
query rewrite
  -> BM25 + Vector
  -> Fusion
  -> Rerank
  -> RetrievalResult + Evidence
```

规划模型看不到 BM25/Vector/Fusion/Rerank 的底层选择。

## 4. 总体流程

```text
用户问题
  -> 创建 immutable originalGoal
  -> 加载 conversation + execution state
  -> 获取当前上下文可见能力
  -> Planner 决定下一步
       |- CALL_CAPABILITY
       |- ANSWER
       |- NEED_MORE_INFO
       `- STOP
  -> CapabilityInvoker 统一校验并执行
  -> 记录 Observation / Evidence / Trace
  -> ExecutionGuard 判断预算、重复、无进展
  -> 判断证据是否足以回答 originalGoal
       |- 否：进入下一轮 Planner
       `- 是：生成基于证据的回答
```

## 5. 核心状态模型

### 5.1 AgentExecutionState

最小字段：

```text
originalGoal        final，只读
currentSubGoal      当前子目标
step                当前步骤数
llmCalls            模型调用次数
startedAt           开始时间
observations        工具结果摘要
capabilityCalls     已执行调用指纹
lastProgressHash    上一次有效进展摘要
stopReason          停止原因
evidenceCoverage    FULL / PARTIAL / NONE
```

`originalGoal` 不允许任何更新 API。

### 5.2 停止原因

建议第一阶段采用枚举而不是虚假的连续置信度：

```text
ENOUGH_EVIDENCE
NEED_USER_INPUT
NO_RELIABLE_EVIDENCE
CAPABILITY_UNAVAILABLE
MAX_STEPS
MAX_LLM_CALLS
TIME_BUDGET_EXCEEDED
REPEATED_CALL
NO_PROGRESS
INVALID_CAPABILITY_CALL
PERMISSION_DENIED
```

### 5.3 EvidenceCoverage

```text
FULL     关键结论均有证据支持
PARTIAL  只能支持部分结论
NONE     没有可靠证据
```

第一阶段不输出未经离线校准的 `0.83` 这类 confidence 数字。

## 6. 能力契约

每个能力必须显式定义：

```text
name
version
description
input schema
required arguments
output type
readOnly
required permissions
maxRows
timeoutMs
```

能力实现接口示意：

```java
public interface KnowledgeCapability {
    CapabilityDefinition definition();
    CapabilityResult execute(CapabilityInvocationContext context,
                             Map<String, Object> arguments);
}
```

能力注册表只返回当前请求真正可见的能力：

```text
全部能力
  -> 按领域过滤
  -> 按知识库能力过滤
  -> 按用户权限过滤
  -> 按环境/开关过滤
  -> Planner 可见能力
```

“动态注册”不等于“全量暴露”。

## 7. 统一调用器

`CapabilityInvoker` 是模型和业务能力之间的唯一入口，职责：

1. 能力是否存在。
2. 当前请求是否可见。
3. 参数 schema 校验。
4. 拒绝模型覆盖系统范围。
5. 权限校验。
6. 超时、最大行数、只读约束。
7. 生成调用指纹用于重复检测。
8. 执行能力。
9. 把结果转为统一 Observation/Evidence。
10. 写入 Trace。

禁止 Planner 直接调用 Mapper、Elasticsearch Client 或底层检索实现。

## 8. 执行预算与防循环

第一阶段必须具备硬限制：

```text
maxSteps
maxLlmCalls
maxElapsedMs
重复调用检测
重复结果检测
无进展检测
```

不能把旧的 `maximumIterations=2` 简单改成更大的数字。

建议调用指纹：

```text
capabilityName + normalizedArguments + systemScopeFingerprint
```

同一指纹在没有新证据/新用户输入的情况下重复出现，直接停止或拒绝再次执行。

## 9. 证据与可回答性

### 9.1 证据必须保留来源

每个能力结果都需要尽可能携带：

```text
sourceType
sourceId / docId
chunkId / recordId
field
rawValue / excerpt
retrievalScore（若有）
rerankScore（若有）
traceStep
```

### 9.2 answerable

`answerable=true` 的必要条件：

- 未因权限/超时/非法调用中断；
- 对原始目标的关键结论存在可靠证据；
- `EvidenceCoverage=FULL`，或产品策略明确允许 PARTIAL 且回答清楚标明范围。

`answerable=false` 时必须给出结构化 `stopReason/refusalReason`，不能用“规划服务失败”笼统覆盖所有失败。

## 10. 与现有系统的集成策略

V1.1 不推翻现有模块：

- 知识库、文档、权限 Service：复用。
- Domain/Slot：可作为某些能力内部的结构化辅助，但不再成为唯一总路由。
- 结构化专利查询：包装成明确能力。
- 现有混合检索 + rerank：包装成一个知识检索能力。
- Evidence/Trace：继续使用并扩展为 Agent 步骤链路。
- ModelApi/Prompt：继续使用，新增 Planner 协议 prompt。

迁移采用纵向切片，不“大爆炸”替换：

1. 先落 Agent 核心协议、只读状态、能力契约、执行守卫。
2. 包装现有知识检索能力。
3. 接入 Planner，在开关下运行。
4. 接入结构化专利能力。
5. 替换旧 V3 顶层固定 Intent 编排。
6. 保留旧链路一段时间用于回归/对照，稳定后删除。

## 11. 第一阶段代码模块

建议新增：

```text
service/agent/
  AgentActionType
  AgentStopReason
  EvidenceCoverage
  AgentDecision
  AgentExecutionState
  AgentExecutionGuard

service/agent/capability/
  CapabilityDefinition
  CapabilityInvocationContext
  CapabilityResult
  KnowledgeCapability
  CapabilityRegistry
  CapabilityInvoker
```

后续再增加：

```text
KnowledgeRetrievalCapability
PatentStructuredQueryCapability
AgentPlanner
AgentOrchestrator
```

## 12. 回归与验收标准

### 12.1 必测回归：检索候选污染

用户问题：

```text
现在专利库里面有名称相近的专利吗？
```

验收：

1. `originalGoal` 始终等于用户原始问题。
2. 任一候选专利标题只能进入 Observation/Evidence。
3. 后续子查询可以引用候选，但不得把候选标题改写成新的原始目标。
4. 最终答案必须回答“库中不同专利之间是否存在名称相近”，而不是回答“某个候选专利是什么”。

### 12.2 通用回归集

必须覆盖：

- 同义问题不同说法。
- 从未出现过的新问法。
- 多轮“它/这个/刚才那个”指代。
- 精确事实查询。
- 复杂多步问题。
- 无答案问题。
- 错误前提问题。
- 检索候选污染。
- Planner 选错能力。
- 重复能力调用。
- 无进展循环。
- 证据不足却尝试回答。
- 权限/知识库范围越界。

### 12.3 工程验收

- 所有 Agent 执行均有 traceId。
- 每一步可看到 action、capability、参数摘要、耗时、结果摘要、证据数量、停止原因。
- 任何失败可区分是规划、参数、权限、能力执行、证据不足还是预算耗尽。
- 新增一种问法不应要求新增 Intent 才能运行。

## 13. 开发纪律

从本版本开始，出现新问题时按以下顺序处理：

1. 先判断是否违反通用架构约束。
2. 再判断是否缺少可复用的系统能力。
3. 再判断 Planner/能力描述/证据契约是否不足。
4. 最后才考虑领域专用实现。

禁止以“再加一个 Intent / 再补一个 if”作为默认修复方式。

---

本文件是 V1.1 的开发基线。后续实现若与本文冲突，应先更新并评审架构文档，再修改代码。