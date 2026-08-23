# Conversation ↔ Knowledge Base 商用化设计

日期：2026-08-23  
范围：`yudao-module-chat`、知识问答工作台、会话数据迁移

## 目标

让一个 Conversation 在创建时固定绑定一个 Knowledge Base，并让后续每一轮问答都由后端从 Conversation 恢复知识库与领域上下文。用户切换知识库时创建新 Conversation，避免历史上下文与检索范围串库。

本次不扩展 Query Planner、Evidence 或 Trace 的内部算法，只稳定它们之间的会话边界和前端消费协议。

## 现状与问题

- `ai_conversation` 当前通过兼容字段 `kb_ids` 保存逗号分隔的知识库列表。
- `ChatPipeline` 当前允许每次请求携带 `kbIds`，并可覆盖会话绑定。
- 新会话创建后才有 Conversation ID，已有代码已修复为先创建再绑定，但绑定仍是可覆盖的多值模型。
- 工作台打开历史会话时没有以服务端会话信息恢复知识库；发送时始终使用当前下拉框的 `kbId`。
- 会话历史接口返回的会话对象缺少明确的 `kbId/domainCode/userId` 协议字段。

## 方案

采用新增单值字段并兼容旧字段的渐进迁移方案：

1. 为 `ai_conversation` 增加 `kb_id`、`domain_code`、`user_id`。
2. 迁移时将旧 `kb_ids` 的第一个有效 ID 回填到 `kb_id`；领域从知识库读取，无法读取时使用 `GENERAL`。
3. 保留 `kb_ids` 一段时间，仅用于旧数据读取兼容，不再由新逻辑写入。
4. 新会话必须在请求中提供 `kbId`，后端校验知识库存在、租户可见并保存领域。
5. 已有会话发送只允许 `conversationId + message`；后端从会话获取 `kbId/domainCode`。
6. `kbId` 与 `domainCode` 没有更新接口；不同知识库必须新建会话。

### 方案取舍

- 仅复用 `kb_ids`：改动小，但无法表达单库不变式，且保留了错误的多库语义。
- 直接删除 `kb_ids`：模型最干净，但会损失历史会话兼容性。
- 新增单值字段并兼容回填：保留历史数据，同时让新链路具备明确不变式，因此作为本次实现方案。

## 后端设计

### 数据模型

`AiConversationDO` 增加：

- `kbId`：固定绑定的知识库 ID，可为空以兼容未完成迁移的旧会话。
- `domainCode`：创建时从知识库快照的领域代码。
- `userId`：创建会话的登录用户 ID。

`tenant_id` 继续由 `TenantBaseDO` 和框架租户拦截器维护。`kb_ids` 保留在 DO 中用于迁移期回退读取，但新服务不写入该字段。

新增 Flyway `V14__chat_conversation_context.sql`，使用 INFORMATION_SCHEMA 条件 DDL 保证重复执行安全，并建立 `(tenant_id, user_id, kb_id)` 辅助索引。回填规则只填充 `kb_id IS NULL` 的记录，不覆盖已有新字段。

### 创建与发送流程

新会话：

```text
ChatSendReq(kbId, message)
    → 校验登录用户与知识库权限
    → 读取知识库 domainCode
    → 创建 Conversation(kbId, domainCode, userId)
    → 写入 USER 消息
    → 使用该会话的 kbId 执行证据链路
```

已有会话：

```text
ChatSendReq(conversationId, message)
    → 按 tenantId + userId 读取 Conversation
    → 恢复 kbId/domainCode
    → 写入 USER 消息
    → 使用恢复出的 kbId 执行证据链路
```

请求中的旧 `kbIds` 不再参与已有会话的路由。兼容期可以接受该字段但必须忽略；为尽早发现错误调用，服务层不得将其写回会话。

### 权限与错误

- 不存在、已删除、跨租户或当前用户不可访问的 Conversation 统一按“会话不存在/无权访问”处理，避免泄漏其他用户会话。
- 新建时知识库不存在或不可访问返回知识库业务错误。
- 已有会话试图绑定不同知识库返回会话上下文冲突业务错误；正常前端不应触发该分支。
- 已关闭会话继续发送保持现有关闭会话行为。

错误码应使用现有 `ErrorCodeConstants` 的业务异常机制；若现有错误码没有对应语义，新增稳定错误码，不返回笼统的 500 系统异常。

### 返回协议

保留 `reply/answerable/confidence/citations/evidenceList/traceId`，新增或补齐：

- `conversationId`
- `messageId`：本次 AI 消息或可确定的最终消息 ID
- `route`、`intent`：本次查询路由和意图，缺失时为空
- `degraded`：是否走降级路径，缺失时为 `false`

本次不强行改造证据服务内部返回；Chat 层将已有数据映射到统一结构，后续 Query Planner 可继续扩展。

## 前端设计

- 新会话：用户选择知识库后首次发送，由后端创建并返回 Conversation；创建成功后会话固定显示该知识库。
- 历史会话：点击会话后先读取 `/history`，以返回的 `conversation.kbId/domainCode` 恢复下拉框、领域标签和当前会话状态。
- 切换知识库：如果当前已有 Conversation，先清空当前消息并视为新会话；不会把新库带入旧 Conversation。
- 发送：新会话请求携带 `kbId`；已有会话只携带 `conversationId/message/channel`，不发送 `kbIds`。
- 输入框：URL 没有 `kbId` 时仍可输入；发送新会话时提示先选库。已有会话不依赖当前 URL 参数。
- 历史会话列表显示会话绑定的知识库名称或领域信息，避免用户误判当前检索范围。

## 测试设计

后端先以红测驱动以下行为：

1. 新会话携带 `kbId` 时先创建并持久化绑定，之后调用证据链路使用该 ID。
2. 已有会话发送时忽略请求中的不同 `kbIds`，始终使用会话绑定的 `kbId`。
3. 已有会话不能被重新绑定知识库。
4. 会话读取/发送需要匹配当前租户和用户。
5. 历史接口返回 `kbId/domainCode`。
6. 前端 API 类型与工作台构建通过，且发送请求在新旧会话两种状态下符合协议。

完成后执行后端 Chat 模块测试、相关 Maven 编译，以及前端类型检查/构建；不将文档文件加入提交。

## 非目标

- 本次不删除旧 AI 菜单。
- 本次不实现 Exact Claim、Scoped Vector hard filter 或大规模专利结构化索引。
- 本次不重写主题 CSS，不改变现有证据评估算法。
- 本次不自动提交或合并代码。
