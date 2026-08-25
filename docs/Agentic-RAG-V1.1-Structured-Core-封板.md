# Agentic RAG V1.1 Structured Core 封板说明

> 日期：2026-08-25  
> 分支：`refactor/knowledge-platform-business-p0`  
> 本文是 `Agentic-RAG-V1.1-架构设计.md` 的结构化执行层补充约束。

## 1. 为什么需要这次收口

V1.1 Agent 上层已经能够把开放问题动态规划为 capability 调用，但第一版 `structured_query` 仍继承了 V3 的不完整结构化模型：字段与 Metric 耦合、TOP_N 偏向 Metric、Schema 声明与真实执行能力可能不一致。结果是 Planner 可以正确理解“最早申请”“不同姓氏”等新问题，却被底层工具契约拒绝。

本次整改的目标不是增加“最早专利”“姓氏统计”等业务规则，而是把结构化执行能力收敛为一套领域无关、可组合、受控的数据处理管道。

## 2. 封板后的执行模型

```text
完整领域实体集合
  -> 读取已注册字段/指标
  -> 多值展开（可选）
  -> 安全值变换（可选）
  -> AND/OR 类型化过滤（可选）
  -> DISTINCT（可选）
  -> GROUP BY（可选）
  -> COUNT/COUNT_DISTINCT/SUM/AVG/MIN/MAX（可选）
  -> 字段/派生值/指标/聚合结果排序（可选）
  -> LIMIT（仅最终输出）
  -> 投影
  -> 确定性答案 + provenance
```

固定的是机器可执行的数据能力，不是用户 Intent。

## 3. Source of Truth

### 3.1 FieldDefinition

字段是否能参与某种运算，完全由 Domain Field Registry 声明：

- `valueType`
- `multiValue`
- `filterable`
- `allowedOperators`
- `sortable`
- `groupable`
- `allowedTransforms`
- `exactIdentifier`

Planner、Capability Compiler、Executor、Schema Contract Test 必须读取同一份定义。

### 3.2 MetricDefinition

指标只有在数据 Adapter 真正可执行时才允许注册给 Planner：

- `supportedOperations`
- `valueType`
- `adapterKey`
- `description`

禁止“先注册、以后再实现”。

## 4. 安全值变换

V1.1 当前只允许白名单变换：

- `LENGTH`
- `YEAR`
- `MONTH`
- `YEAR_MONTH`
- `VALUE_COUNT`
- `PERSON_SURNAME`

这些是物理执行能力，不是用户语义枚举。

例：

```text
标题最长
TITLE -> LENGTH -> ORDER DESC -> LIMIT 1

最早申请
FILING_DATE -> ORDER ASC -> LIMIT 1

每年申请多少件
FILING_DATE -> YEAR -> GROUP -> COUNT

发明人有几个不同姓氏
INVENTOR -> EXPLODE -> PERSON_SURNAME -> COUNT_DISTINCT
```

没有在字段 `allowedTransforms` 中声明的变换，Planner 不得调用，Executor 必须拒绝。

## 5. 多值字段

`multiValue=true` 是执行语义，不再只是描述信息。

- 需要按单个元素过滤/分组/去重时使用 `explode=true`。
- 常见 `、；;,，` 和换行分隔统一处理。
- 重复逻辑实体的多值字段按集合等价比较，元素顺序不同不形成冲突。
- 多维 GROUP BY 的笛卡尔展开有硬预算，超过限制直接停止，不进行无界计算。

## 6. 数据类型必须真实执行

字段声明 `DATE / INTEGER / DECIMAL / STRING` 后：

- DATE 必须能按受支持日期格式解析。
- INTEGER/DECIMAL 必须能数值解析。
- 非法源值视为缺失，由完整性规则处理。
- 过滤字面量必须符合派生后的类型。
- DATE/数字禁止失败后退化成普通字符串比较。

## 7. 完整性与冲突

### 7.1 PARTIAL 默认不可回答

V1.1 当前产品策略：确定性结构化结论要求 FULL。

用户显式要求的投影值、排序值、聚合值、分组值或非 EXISTS 过滤值只要在所需实体中存在缺失，就不能把 PARTIAL 结果包装成 FULL。

### 7.2 逻辑专利实体

PATENT 实体按：

1. 申请号优先；
2. 公布号兜底；
3. 无业务标识时才回退物理 documentId。

相同逻辑专利的重复导入只算一个业务实体。

### 7.3 重复记录冲突

重复记录合并时：

- 单值字段值不一致 => 记录冲突；
- 多值字段按集合比较；
- 只有当前查询真正依赖的冲突字段才阻断；
- 依赖字段冲突必须 fail-closed，禁止静默选择第一条。

## 8. Capability 三层契约

`structured_query` 执行前后依次经过：

### 第一层：CapabilityInvoker

- 参数名白名单；
- required 参数；
- 保护 tenantId/userId/kbId/domainCode 等系统范围；
- capability 的机器参数 validator：JSON 形状、类型、范围；
- timeout/maxRows。

### 第二层：Pipeline Compiler / Domain Schema

- 字段/指标必须注册；
- filter/sort/group/transform 必须被 Schema 允许；
- 不允许跨实体类型拼接伪 JOIN；
- 参数组合必须可执行。

### 第三层：Pipeline Executor

- 数据完整性；
- 实际类型；
- 重复实体冲突；
- 数据集是否截断；
- 分组展开预算；
- 最终 FULL/空集语义。

## 9. Agent 自修复边界

可修复错误最多在总预算内重规划 2 次：

- 未知 capability 参数；
- 缺少 required 参数；
- 参数类型/范围错误；
- 字段/变换/排序/聚合组合违反 capability contract。

不可修复，立即停止：

- Planner 试图覆盖 tenantId/userId/kbId/permissions 等系统范围；
- 权限不足；
- timeout；
- 结构化源数据不完整；
- 查询依赖字段存在真实数据冲突；
- 数据集被截断但问题需要全集结论。

Agent 不允许通过换一种检索方式绕过这些错误。

## 10. Trace 必须可诊断

每个 Planner/Capability 步骤必须记录经过截断和安全处理的参数摘要，例如：

```text
capability=structured_query
arguments={
  select:[TITLE,FILING_DATE],
  orderBy:{field:FILING_DATE,direction:ASC},
  limit:1
}
```

不得记录权限 token、服务端 scope 密钥或完整系统 Prompt。

## 11. Schema 自动契约测试

以后不是等用户问出问题才发现能力残缺。

自动测试必须保证：

- 每个注册字段都有真实 Adapter 支持；
- `sortable=true` => ASC/DESC 可执行；
- `groupable=true` => GROUP 可执行；
- 每个 `allowedOperator` 可执行；
- 每个 `allowedTransform` 能校验并执行；
- 每个 Metric 的 `supportedOperations` 真能执行；
- 多值展开、派生值、缺失值、重复实体冲突均有回归。

## 12. 黑盒验收问题族

以下问题不应新增业务 Intent/if：

```text
哪个专利申请最早？
最近公开的前三件是什么？
标题最长的是哪件？
2024 年后申请最早的三件是什么？
有多少个不同发明人？
发明人总共有几个姓氏？
每个发明人分别参与多少件专利？
每年分别申请多少件？
哪一年申请数量最多？
哪个专利权利要求最多？
```

前提是所需字段/指标和安全变换已经存在且数据完整。

## 13. 后续什么情况才允许新增 Java 能力

新增问法本身不是新增代码的理由。

只有以下情况才允许扩展：

1. 新领域没有所需结构化数据字段；
2. 系统没有真实的物理运算能力，例如新的安全派生函数；
3. 数据规模需要新的索引/ANN/离线计算能力；
4. 新外部数据源/API/权限能力需要接入；
5. 已有执行能力存在通用正确性缺陷。

禁止因为“用户换了一种说法”增加 Intent、关键词 if 或专用 capability。

## 14. 当前限制

- `PERSON_SURNAME` 是受控姓名解析能力，不等于任意语言姓名学推理；无法形成稳定解析时必须返回缺失并 fail-closed。
- Agent 原生多知识库范围仍未在 V1.1 第一阶段开放。
- 超大规模集合相似仍需索引化实现；不能用在线 O(n²) 冒充生产能力。
- CI 必须真实跑绿、真实专利黑盒回归通过后，才允许把默认路由切成纯 Agent 并最终退出 V3 fallback。
