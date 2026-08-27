# Agent 架构升级方案：ReAct + Skills + Workflow

> 版本：v1.0 · 2026-08-27
> 状态：**方案冻结，待后续评审后实施**
> 当前实现基线：`a456c970faa3732d2e1eeeeeb8c5dd551c7b71eb`
> 目标：在不推倒现有 Structured Runtime、证据链和治理能力的前提下，将最外层 Agent 控制器从“一次规划完整 DAG + 有限 Replan”升级为“动态 Agent Loop + Skills + Workflow 编排”。

---

## 1. 为什么要升级

当前系统已经完成了较强的确定性执行底座，但最外层控制方式仍然是：

```text
OriginalGoal
    ↓
LLM Planner
    ↓
一次生成 AgentExecutionPlan / DAG
    ↓
Runtime 执行整个 DAG
    ↓
Goal Evaluator
    ↓
满足 → Answer
不足 → 整体 Replan
```

当前方案的问题不在 Tool、结构化查询或证据链，而在于：**模型在还没有看到上游 Tool 的真实结果前，就被要求提前规划后续节点。**

典型问题：

```text
用户输入近似名称 / 错字
    ↓
Planner 同时规划 semantic retrieval + 后续 structured filter
    ↓
semantic 已经找到正确候选
    ↓
后续节点仍使用原始错字做 TITLE CONTAINS
    ↓
正确候选被再次过滤掉
    ↓
Evaluator 发现不足
    ↓
整份计划 Replan
```

这类问题已经说明：对于未知中间结果的任务，默认控制器更适合“执行一步、观察一步、再决定一步”，而不是要求模型提前猜完整 DAG。

---

## 2. 核心结论

### 2.1 不推倒现有架构

以下能力继续保留，并作为新 Agent Runtime 的确定性底座：

- Structured Query IR / 结构化查询执行器
- Typed Tool Contract / CapabilityDefinition
- CapabilityRegistry
- DomainField / DomainMetric / DomainEntity Registry
- candidateEntityIds → verifiedEntityIds 信任边界
- Typed Dataflow / dataflowRows
- 权限、租户、知识库 Scope
- Runtime Budget / Timeout / Retry
- ActivityRecord
- ReferenceRecord
- ProvenanceRecord
- Goal Evaluator
- supportingReferenceIds / answerReferenceIds
- NoProgressGuard
- Result Integrity / Provenance Integrity

**这些不是旧架构负担，而是 ReAct Agent 真正进入生产环境必须依赖的基础设施。**

### 2.2 要替换的是“主控制器”

当前默认主控制方式：

```text
Planner → 完整 DAG → Execute → Evaluator → Replan
```

目标主控制方式：

```text
Think / Decide
    ↓
Action：Tool / Skill / Workflow
    ↓
Observation
    ↓
Goal Check
    ↓
未完成 → 下一轮 Decide
已完成 → Final Answer
```

即：**Agent Loop 成为默认控制器，DAG 降级为 Workflow 的一种执行形式。**

---

## 3. 目标总体架构

```text
                       OriginalGoal
                            │
                            ▼
                  ┌──────────────────┐
                  │ Agent Controller │
                  │   ReAct Loop     │
                  └────────┬─────────┘
                           │
                     Decide Next
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
      Load Skill        Tool Call       Run Workflow
          │                │                │
          ▼                ▼                ▼
   Skill Registry   CapabilityRegistry  WorkflowRegistry
          │                │                │
          └────────────────┼────────────────┘
                           ▼
                      Observation
                           │
             ┌─────────────┼─────────────┐
             ▼             ▼             ▼
         Activity       Reference     Provenance
                           │
                           ▼
                       Agent State
                           │
                           ▼
                    Goal Evaluator
                      │         │
                    不足        满足
                      │         │
                      └─ loop   ▼
                              Answer
```

架构原则：

> **Agent 负责决定下一步做什么；Tool 负责原子能力；Workflow 负责确定性编排；Skill 负责告诉 Agent 某类任务应该如何使用能力。**

---

## 4. ReAct Agent Loop 的定义

这里的 ReAct 不等于“无限循环”。

正确含义：

> 每一次行动后都让 Agent 基于真实 Observation 重新决定下一步，而不是提前猜测所有后续动作。

建议统一定义一轮 Agent Turn：

```text
1. Build Context
2. Decide Next Action
3. Validate Action
4. Execute Action
5. Materialize Observation
6. Update Reference / Provenance / State
7. Goal Evaluation
8. Continue / Final / Clarify / Stop
```

### 4.1 下一步 Action 类型

建议最外层只允许少量通用 Action：

```text
CALL_TOOL
LOAD_SKILL
RUN_WORKFLOW
FINAL_ANSWER
NEED_MORE_INFO
STOP
```

不要重新引入业务 intent 枚举。

### 4.2 不再使用固定 Replan 次数作为主要控制方式

当前：

```text
MAX_REPLAN_ATTEMPTS = 2
```

目标：取消“最多重新规划两次”这个语义限制，改为统一资源预算：

```text
maxTurns
maxToolCalls
maxLlmCalls
maxElapsedMs
maxTokens
maxCost
noProgressGuard
duplicateActionGuard
```

因此系统是：

> **逻辑路径长度可动态变化，但资源消耗严格有界。**

绝不实现真正无约束的 `while(true)`。

---

## 5. Tool 的定位

Tool 是最小可执行能力，不负责教 Agent “什么时候应该这么做”。

例如：

```text
knowledge_retrieval
structured_query
relation_traversal
entity_set_operation
scalar_compare
exact_text_search
...
```

Tool 必须继续保持：

- typed argument schema
- permission / scope validation
- deterministic validation
- typed output
- candidate / verified trust semantics
- Reference / Provenance
- timeout / retry / idempotency

Agent 无权绕过 Tool Contract 直接访问业务数据源。

---

## 6. Skill 的定位

### 6.1 Skill 不是 Tool

Skill 更像“领域工作说明 + 能力装载包”。

它解决的问题是：随着领域和 Tool 增加，不能每次把全部字段、全部能力、全部规则塞进 Agent Prompt。

### 6.2 Skill 建议包含

```text
SkillDefinition
├── name
├── description
├── supportedDomains
├── triggerHints / capabilityHints
├── allowedTools
├── allowedWorkflows
├── field / metric / relation schema refs
├── operatingRules
├── trustRules
├── examples
├── outputExpectations
└── version
```

例如 Patent Skill：

```text
PatentKnowledgeSkill

允许能力：
- knowledge_retrieval
- structured_query
- relation_traversal
- entity_set_operation

领域知识：
- TITLE
- APPLICATION_NO
- PUBLICATION_NO
- INVENTOR
- APPLICANT
- FILING_DATE
...

规则：
- 模糊专利名称可先 semantic 定位候选
- candidate 不等于 verified
- INVENTOR 为 multiValue
- multiValue 元素级计算需要 explode
- 全集聚合必须确认 coverageComplete
```

### 6.3 Skill 的加载方式

Agent 初始上下文只包含：

- OriginalGoal
- 少量系统规则
- Skill Catalog 摘要
- 通用 Tool 摘要

Agent 根据问题选择：

```text
候选 Skills → Load Skill → 获得该领域完整能力说明
```

需要配置：

```text
candidateSkillCount
maxLoadedSkills
skillContextTokenBudget
```

Skill 加载也要可观测，形成 Activity。

---

## 7. Workflow 的定位

Workflow 不是 ReAct 的替代品，而是 ReAct 可以调用的一种确定性能力。

### 7.1 适合 Agent 动态决定的事情

```text
下一步查什么？
当前结果是否需要补证？
这个模糊对象可能是谁？
应该加载哪个 Skill？
应该调用哪个 Workflow？
```

### 7.2 适合 Workflow 的事情

当执行逻辑已经明确时，不要每一步都重新调用 LLM。

例如：

```text
按申请人统计专利数
→ 排序取 Top10
→ 对 Top10 统计发明人数
→ 合并结果
```

Workflow 可以直接执行：

```text
            structured source
                   │
             group applicant
                   │
                count
                   │
             order top10
                   │
          ┌────────┴────────┐
          ▼                 ▼
    patent count      inventor count
          │                 │
          └────────┬────────┘
                   ▼
                  merge
```

这样比纯 ReAct 每一步都 LLM → Tool 更快、更便宜、更稳定。

### 7.3 WorkflowRegistry

建议增加：

```text
WorkflowRegistry
WorkflowDefinition
WorkflowExecutor
WorkflowRunRecord
```

Workflow 可以来源于：

- 代码预定义
- 管理后台可视化编排
- 受控的 Planner 动态生成

动态生成的 Workflow 仍必须经过静态 Validation 后才能执行。

---

## 8. 当前 DAG 的去向

**不删除 AgentExecutionPlan / PlanNode。**

但职责变化：

当前：

```text
AgentExecutionPlan = Agent 默认决策单元
```

目标：

```text
AgentExecutionPlan = Workflow 的一种机器表达
```

即：

- 简单 Agent 行为：一次只调用一个 Tool
- 明确多步骤 deterministic 行为：Agent 调用 Workflow
- Workflow 内部仍然可以是 DAG
- 需要 fan-out / fan-in 时继续发挥 DAG 优势

因此前面做的 typed `$ref`、dataflowRows、依赖校验、并行执行都继续保留。

---

## 9. Goal Evaluator 的新角色

Goal Evaluator 不删除，反而升级成 Agent Loop 的完成闸门。

目标流程：

```text
Action
  ↓
Observation
  ↓
Goal Evaluator
  ├── SATISFIED → Final
  ├── INSUFFICIENT → Continue Loop
  ├── NEED_MORE_INFO → Clarify
  └── FAILED → Fail Closed / Retry Policy
```

保留：

```text
supportingReferenceIds
answerReferenceIds
```

最终 Answer 仍然只能读取 `answerReferenceIds`，不能重新把完整执行历史塞进回答层。

---

## 10. Agent State

新 Runtime 应把“计划历史”升级为统一 Agent State。

建议：

```text
AgentState
├── originalGoal
├── currentGoalState
├── loadedSkills
├── observations
├── references
├── provenance
├── verifiedContextEntityIds
├── candidateContext
├── completedActions
├── failedActions
├── workflowRuns
├── turn
├── toolCallCount
├── llmCallCount
├── elapsedMs
├── tokenUsage
└── costUsage
```

任何下一轮决策都基于 State，而不是通过自然语言 summary 猜上一轮发生了什么。

---

## 11. Action Validation

Agent Loop 不能因为变灵活就降低安全性。

所有 Action 执行前统一进入：

```text
AgentActionValidator
```

需要继续检查：

- Tool 是否真实存在
- Skill 是否允许使用该 Tool
- Tool arguments 是否满足 schema
- 权限 / tenant / kb scope
- candidate / verified 信任边界
- 内部 ID provenance
- 重复调用
- 自引用 / 循环数据流
- 不完整数据继续计算
- 预算
- 写操作审批

原来的 `AgentExecutionPlanValidator` 可拆出通用规则复用，而不是全部删除。

---

## 12. 性能原则

ReAct 如果每一步都调用 LLM，会比当前系统更慢，因此必须采用“动态决策 + 确定性批执行”混合模式。

原则：

1. Agent 只在真正需要决策时调用 LLM。
2. 确定性连续步骤优先合并进 Workflow。
3. Tool 结果足够时直接 deterministic answer fast path。
4. semantic retrieval 只用于发现 / 正文证据，不机械追加第二条检索路径。
5. Skill 采用按需加载，减少 Prompt token。
6. 同一 Observation 不重复 Evaluator + Planner 做相同判断。
7. 支持 Tool 并行，但必须是真正独立的数据需求。
8. 对昂贵 Tool 建立成本和延迟元数据，供 Agent Controller 决策。

目标不是“Agent 能多跑几轮”，而是：

> **需要时可以多跑，但简单问题必须比现在更短。**

---

## 13. 可观测性 / 运营平台

ReAct 后执行步骤会增加，现有 Trace 更重要。

建议 Trace 从阶段列表升级为统一 Event Timeline：

```text
AGENT_DECISION
SKILL_LOAD
TOOL_CALL
TOOL_RESULT
WORKFLOW_START
WORKFLOW_NODE
WORKFLOW_END
GOAL_EVALUATION
FINAL_ANSWER
STOP
```

每条事件继续关联：

```text
traceId
turnId
actionId
referenceId
provenance
elapsedMs
status
budget snapshot
```

运营平台应能直接回放：

> 用户问了什么 → Agent 为什么选择这个 Skill → 调了什么 Tool → Tool 返回什么 → 为什么继续 → 哪些 Reference 最终进入答案。

---

## 14. 建议新增核心组件

第一版类级设计建议：

```text
AgentLoopRuntime
AgentDecisionModel
AgentAction
AgentActionType
AgentActionValidator
AgentState
AgentBudgetController
AgentLoopGuard

SkillRegistry
SkillDefinition
SkillLoader
SkillSelectionService

WorkflowRegistry
WorkflowDefinition
WorkflowExecutor
WorkflowRunRecord

ToolActionExecutor
SkillActionExecutor
WorkflowActionExecutor
```

现有组件继续复用：

```text
CapabilityRegistry
CapabilityInvoker
AgentRuntimeExecutor（逐步下沉为 Workflow/Tool Executor）
StructuredPipelineCapabilityDelegate
AgentGoalEvaluator
ReferenceRecord
ActivityRecord
ProvenanceRecord
NoProgressGuard
```

---

## 15. 平滑迁移步骤

### Phase 0：冻结当前实现

基线：

```text
a456c970faa3732d2e1eeeeeb8c5dd551c7b71eb
```

当前版本继续作为行为回归基线，不再通过无限增加 Planner Prompt 规则作为长期架构方向。

### Phase 1：引入 AgentAction + AgentLoopRuntime

先支持：

```text
CALL_TOOL
FINAL_ANSWER
NEED_MORE_INFO
STOP
```

现有 Tool Contract 完全复用。

先让简单单 Tool 问题通过新 Loop 跑通。

### Phase 2：把 Goal Evaluator 接入每轮 Observation

去掉“执行完整 DAG 后才 Evaluate”的绑定。

每个有效 Observation 后可以判定：

```text
SATISFIED / CONTINUE / CLARIFY / STOP
```

### Phase 3：Skills

增加 SkillRegistry 和按需加载。

先实现 Patent Skill，验证：

- 不再每次加载全部专利领域规则
- Tool/schema 动态发现仍然成立
- 新增领域只注册 Skill + Domain Pack，不修改 Agent Controller

### Phase 4：Workflow

把现有 `AgentExecutionPlan + AgentRuntimeExecutor` 包装成 Workflow Executor。

Agent 可以选择：

```text
CALL_TOOL
或
RUN_WORKFLOW
```

已有 DAG 测试全部迁移成 Workflow Regression。

### Phase 5：可视化编排

管理后台提供：

- Workflow 节点编排
- Skill 绑定
- Tool 白名单
- 输入输出映射
- 版本发布
- 调试回放

### Phase 6：旧 Planner 降级

`LlmAgentExecutionPlanner` 不再是全局主控制器。

可保留为：

```text
WorkflowPlanner
```

只有 Agent 判断“当前问题适合一次生成确定性 DAG”时才调用。

---

## 16. 第一批回归场景

升级完成必须至少保证以下场景：

### A. 精确结构化查询

```text
申请号 XXX 的公布号是什么？
```

理想：1 次 Agent decision + 1 次 structured_query。

### B. 模糊实体 → 详情

```text
帮我检索体替代印花的专利详情
```

理想：

```text
retrieval → observe candidate → structured detail → final
```

不应整体 Replan。

### C. 极值 + 详情

```text
哪个专利发明人最多？哪个最少？罗列专利名字和发明人
```

可以由 Agent 选择一个 deterministic Workflow，一次完成多个结构化步骤。

### D. 语义正文问题

如果 semantic Evidence 已经直接回答目标，不得机械调用 structured_query。

### E. 多轮上下文

已 verified 的实体进入 AgentState，后续“它的申请人是谁？”直接基于 verified context，不重新猜实体。

### F. 无进展循环

同一 Tool + 等价参数 + 等价 Observation 连续出现时必须触发 NoProgressGuard。

---

## 17. 验收标准

架构升级不能只看“能跑”。至少满足：

### 正确性

- 不因提前规划未知中间结果而产生错误下游条件
- candidate 永远不能直接升级成 verified
- 最终回答只消费 answerReferenceIds
- deterministic 计算不由 LLM 临场补算

### 泛化

- Agent Controller 不出现 Patent 专用 if/else
- 新领域通过 Skill / Domain Pack / Tool Contract 扩展
- 不枚举自然语言业务意图

### 性能

- 简单结构化问题调用链不长于当前版本
- 模糊实体详情问题避免“执行失败 → Evaluator → 整体 Replan”
- 确定性多步骤优先 Workflow，不逐节点反复调用 LLM

### 可治理

- 所有 Action 有 Activity / Reference / Provenance
- 所有 Agent Loop 有预算
- 所有写 Tool 有权限与审批边界
- 可完整回放每一次 Observation 和后续决策

---

## 18. 明确不做的事情

本次升级禁止走以下方向：

1. 不做真正无限轮数 Agent。
2. 不把全部安全规则交给 LLM Prompt。
3. 不删除 Typed Tool Contract。
4. 不删除 Structured Query IR。
5. 不删除 Goal Evaluator。
6. 不让 semantic candidate 自动成为 trusted entity。
7. 不为了“Agent 化”把确定性计算全部改成 LLM tool-call 循环。
8. 不把 Skill 做成新的业务 intent 枚举。
9. 不把 Workflow 写成专利专用流程集合。
10. 不再以持续增加 Planner v10/v11/v12 Prompt 特例作为主演进方式。

---

## 19. 最终架构判断

现阶段不是“之前方案做错了”，而是：

```text
之前完成的是生产级 Agent 的执行底座；
下一步需要升级的是 Agent 的决策发动机。
```

最终架构一句话：

> **ReAct Agent Loop 负责动态决策，Skills 负责按需装载领域知识和能力，Workflow/DAG 负责确定性编排，Typed Tools 负责安全执行，Reference/Provenance 负责证据链，Goal Evaluator 负责完成判定。**

后续实现前先基于本文档做一次正式评审，确认类级设计、状态模型、Skill Contract、Workflow Contract 和迁移顺序后再开始大规模重构。
