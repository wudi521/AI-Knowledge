# Conversation ↔ Knowledge Base 商用化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 固化 Conversation 与单一 Knowledge Base 的绑定，让后续问答由后端恢复 `kbId/domainCode`，并让知识问答工作台正确恢复历史会话上下文。

**Architecture:** 在 `ai_conversation` 增加单值上下文字段，旧 `kb_ids` 只作迁移期回退读取。ChatPipeline 在创建会话时通过 KnowledgeApi 校验当前用户可见的知识库并保存领域；已有会话按租户/用户读取绑定范围，忽略每轮请求携带的旧 `kbIds`。前端根据历史接口返回的会话上下文驱动当前知识库，并在切换知识库时开始新会话。

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, OpenFeign, JUnit 5, Mockito, Flyway, Vue 3, TypeScript, Ant Design Vue, pnpm.

---

## 文件地图

后端：

- Create: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-server/src/main/resources/db/migration/V14__chat_conversation_context.sql` — 增加会话上下文字段、索引和旧数据回填。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/dal/dataobject/conversation/AiConversationDO.java` — 映射 `kbId/domainCode/userId`。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/service/conversation/ConversationService.java` — 创建绑定、用户范围读取、immutable 校验和旧字段回退。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-api/src/main/java/cn/iocoder/yudao/module/chat/enums/ErrorCodeConstants.java` — 新增知识库不可用、会话上下文冲突业务错误。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/chat/vo/ChatSendReqVO.java` — 增加单值 `kbId`，保留 `kbIds` 兼容入参但不作为新链路依据。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/chat/ChatController.java` — 将新协议传入 Pipeline。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/service/chat/ChatPipeline.java` — 按会话恢复知识库，完成创建校验，禁止覆盖绑定，并返回消息 ID。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/service/chat/ChatSendResult.java` — 增加 `messageId/kbId/domainCode/route/intent/degraded` 协议字段。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/chat/vo/ChatSendRespVO.java` — 暴露稳定响应字段。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/conversation/vo/ConversationInfoVO.java` — 返回会话绑定上下文。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/conversation/ConversationController.java` — 历史接口按当前用户读取会话。
- Test: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/test/java/cn/iocoder/yudao/module/chat/service/chat/ChatPipelineTest.java` — 新建/已有会话绑定行为的红绿测试。
- Create: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/yudao-module-chat/yudao-module-chat-server/src/test/java/cn/iocoder/yudao/module/chat/service/conversation/ConversationServiceTest.java` — 服务层创建、绑定不可变和用户读取测试。

前端：

- Modify: `/Users/wudi/IdealProjects/myself/yudao-ui-admin-vben-master/apps/web-antd/src/api/ai/chat/index.ts` — 更新 Conversation/SendResp 类型及新旧请求构造。
- Modify: `/Users/wudi/IdealProjects/myself/yudao-ui-admin-vben-master/apps/web-antd/src/views/ai/workbench-vben/index.vue` — 恢复历史知识库、切库新会话、按会话发送、放开无 URL kbId 的输入。

文档：

- Existing: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/docs/superpowers/specs/2026-08-23-conversation-kb-context-design.md` — 已确认设计，不加入代码提交。
- Create: `/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/docs/superpowers/plans/2026-08-23-conversation-kb-context.md` — 本实现计划，不加入代码提交。

### Task 1: Add the database and domain model for an immutable single-KB conversation

**Files:**
- Create: `yudao-server/src/main/resources/db/migration/V14__chat_conversation_context.sql`
- Modify: `yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/dal/dataobject/conversation/AiConversationDO.java`
- Modify: `yudao-module-chat/yudao-module-chat-api/src/main/java/cn/iocoder/yudao/module/chat/enums/ErrorCodeConstants.java`
- Test: `yudao-module-chat/yudao-module-chat-server/src/test/java/cn/iocoder/yudao/module/chat/service/conversation/ConversationServiceTest.java`

- [ ] **Step 1: Write the failing service tests**

Add Mockito tests that capture the `AiConversationDO` passed to `AiConversationMapper.insert` and assert that the knowledge-aware creation method writes the requested `kbId`, `domainCode`, and `userId`. Add a second test that stubs a conversation with `kbId=6` and asserts that attempting to bind `kbId=7` throws the new context-conflict error and never calls `updateById`.

```java
@Test
void createKnowledgeConversationPersistsBindingAndOwner() {
    service.createConversation("WEB", null, 6L, "PATENT", 42L);

    ArgumentCaptor<AiConversationDO> captor = ArgumentCaptor.forClass(AiConversationDO.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getKbId()).isEqualTo(6L);
    assertThat(captor.getValue().getDomainCode()).isEqualTo("PATENT");
    assertThat(captor.getValue().getUserId()).isEqualTo(42L);
}

@Test
void rebindKnowledgeConversationRejectsDifferentKb() {
    AiConversationDO conversation = new AiConversationDO();
    conversation.setId(9L);
    conversation.setKbId(6L);
    when(mapper.selectById(9L)).thenReturn(conversation);

    assertThatThrownBy(() -> service.ensureBoundKb(9L, 7L, 42L))
            .isInstanceOf(ServiceException.class)
            .extracting("code").isEqualTo(CONVERSATION_CONTEXT_CONFLICT.getCode());
    verify(mapper, never()).updateById(any());
}
```

- [ ] **Step 2: Run the focused test and verify it fails for the missing API**

Run from the backend repository:

```bash
mvn -o -pl yudao-module-chat/yudao-module-chat-server -Dtest=ConversationServiceTest test
```

Expected: compilation/test failure because the knowledge-aware creation and immutable binding methods/fields do not exist yet.

- [ ] **Step 3: Add the migration and DO fields**

Create V14 with conditional DDL for `kb_id bigint`, `domain_code varchar(32)`, and `user_id bigint`, plus an index on `(tenant_id, user_id, kb_id)`. Backfill only null `kb_id` values from the first numeric token in `kb_ids`; join `ai_knowledge_base` to copy `domain_code`, defaulting it to `GENERAL`. Do not infer `user_id` from the string `creator`.

Add these fields to `AiConversationDO`:

```java
/** 固定绑定的知识库编号 */
private Long kbId;

/** 创建时快照的知识领域 */
private String domainCode;

/** 创建会话的用户编号 */
private Long userId;
```

Add stable chat errors:

```java
ErrorCode KNOWLEDGE_BASE_NOT_EXISTS = new ErrorCode(1_003_000_005, "知识库不存在或无权访问");
ErrorCode CONVERSATION_CONTEXT_CONFLICT = new ErrorCode(1_003_000_006, "会话已绑定其他知识库，请新建会话");
```

- [ ] **Step 4: Implement the minimum service behavior**

Add `createConversation(String channel, String customerId, Long kbId, String domainCode, Long userId)`, preserving the old two-argument overload only for non-knowledge callers. Set `kbId/domainCode/userId` before `insert`. Add `getConversationForUser(Long id, Long userId)` that returns the conversation only when the stored `userId` matches; for migrated rows with null `userId`, parse a numeric `creator` as a compatibility owner when possible. Add `ensureBoundKb(Long conversationId, Long kbId, Long userId)` that throws `CONVERSATION_CONTEXT_CONFLICT` on a different non-null binding and otherwise leaves the row unchanged.

- [ ] **Step 5: Run the focused service tests**

```bash
mvn -o -pl yudao-module-chat/yudao-module-chat-server -Dtest=ConversationServiceTest test
```

Expected: the new service tests pass.

### Task 2: Resolve visible knowledge-base context at the Chat boundary

**Files:**
- Modify: `yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/chat/vo/ChatSendReqVO.java`
- Modify: `yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/service/chat/ChatPipeline.java`
- Modify: `yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/chat/ChatController.java`
- Test: `yudao-module-chat/yudao-module-chat-server/src/test/java/cn/iocoder/yudao/module/chat/service/chat/ChatPipelineTest.java`

- [ ] **Step 1: Write the failing pipeline tests**

Extend `ChatPipelineTest` with two behaviors:

```java
@Test
void newConversationUsesValidatedKbAndDoesNotBindRequestList() {
    when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(6L)));
    when(knowledgeApi.getKbDomainCodes(List.of(6L)))
            .thenReturn(CommonResult.success(Map.of(6L, "PATENT")));
    when(conversationService.createConversation("WEB", null, 6L, "PATENT", 42L))
            .thenReturn(conversation(100L, 6L, "PATENT", 42L));
    when(evidenceRpcAdapter.evaluate(any(), eq(tenantId), eq(42L), any(), any(), eq(List.of(6L))))
            .thenReturn(null);

    pipeline.send(null, "问题", "web", null, 6L);

    verify(conversationService).createConversation("WEB", null, 6L, "PATENT", 42L);
    verify(conversationService, never()).bindKbIds(any(), anyList());
}

@Test
void existingConversationUsesPersistedKbEvenWhenLegacyRequestContainsAnotherKb() {
    when(conversationService.getConversationForUser(100L, 42L))
            .thenReturn(conversation(100L, 6L, "PATENT", 42L));
    when(evidenceRpcAdapter.evaluate(any(), any(), eq(42L), any(), any(), eq(List.of(6L))))
            .thenReturn(null);

    pipeline.send(100L, "追问", "WEB", null, 7L);

    verify(evidenceRpcAdapter).evaluate(any(), any(), eq(42L), any(), any(), eq(List.of(6L)));
    verify(conversationService, never()).bindKbIds(any(), anyList());
}
```

Configure `KnowledgeApi`, `SecurityFrameworkUtils` login user, and the persisted conversation in the test fixture; keep the existing create-before-bind regression test only until the old overload is removed from production usage.

- [ ] **Step 2: Run the focused pipeline test and verify the new tests fail**

```bash
mvn -o -pl yudao-module-chat/yudao-module-chat-server -Dtest=ChatPipelineTest test
```

Expected: compilation or verification failure because the request does not expose `kbId`, the Pipeline still accepts `kbIds`, and `KnowledgeApi` is not injected.

- [ ] **Step 3: Change the request/controller contract**

Add to `ChatSendReqVO`:

```java
@Schema(description = "新会话绑定的知识库编号; 已有会话由后端从会话恢复")
private Long kbId;
```

Keep `kbIds` without using it in new logic for a short compatibility window. Change `ChatController` to call `chatPipeline.send(req.getConversationId(), req.getMessage(), req.getChannel(), req.getCustomerId(), req.getKbId())`.

- [ ] **Step 4: Implement validated creation and persisted-scope sending**

Inject `KnowledgeApi` into `ChatPipeline`. At the beginning of `send`, obtain `LoginUser`, `tenantId`, and `userId`. For a new conversation, require `kbId`, call `getVisibleKbIds(userId)`, and reject a null/failed response or a set that does not contain `kbId` with `KNOWLEDGE_BASE_NOT_EXISTS`. Call `getKbDomainCodes(List.of(kbId))`, default a blank domain to `GENERAL`, create the conversation with the binding, and use `List.of(kbId)` for evidence.

For an existing conversation, call `getConversationForUser(conversationId, userId)` and use its `kbId`; if it is null, parse the first legacy `kb_ids` ID and validate it through the same KnowledgeApi path before continuing. Never call `bindKbIds` in the new flow. Treat a non-null `kbId` in a subsequent request as a compatibility input only; it must not replace the persisted value.

- [ ] **Step 5: Run the focused pipeline tests**

```bash
mvn -o -pl yudao-module-chat/yudao-module-chat-server -Dtest=ChatPipelineTest test
```

Expected: all create-before-scope and persisted-scope tests pass, including the original regression test adapted to the new signature.

### Task 3: Return stable message and conversation context fields

**Files:**
- Modify: `yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/service/chat/ChatSendResult.java`
- Modify: `yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/chat/vo/ChatSendRespVO.java`
- Modify: `yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/service/chat/ChatPipeline.java`
- Modify: `yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/conversation/vo/ConversationInfoVO.java`
- Modify: `yudao-module-chat/yudao-module-chat-server/src/main/java/cn/iocoder/yudao/module/chat/controller/admin/conversation/ConversationController.java`
- Test: `yudao-module-chat/yudao-module-chat-server/src/test/java/cn/iocoder/yudao/module/chat/service/chat/ChatPipelineTest.java`

- [ ] **Step 1: Write failing response assertions**

Add a test where `messageService.addMessage` returns an `AiMessageDO` with `id=3021`, and assert that the answer result contains `messageId=3021`, `conversationId=100`, the bound `kbId`, and `degraded=false`. The `ConversationInfoVO` fields are verified by compiling the existing `BeanUtils.toBean` mapping and by the frontend typecheck in Task 4.

- [ ] **Step 2: Run the focused test and verify it fails**

```bash
mvn -o -pl yudao-module-chat/yudao-module-chat-server -Dtest=ChatPipelineTest test
```

Expected: failure because `ChatSendResult` has no `messageId/kbId/degraded` and the Pipeline currently discards the persisted AI message.

- [ ] **Step 3: Implement the response mapping**

Add nullable fields to both result/response classes:

```java
private Long messageId;
private Long kbId;
private String domainCode;
private String route;
private String intent;
private Boolean degraded;
```

Capture the return value of `messageService.addMessage` in `buildAnswerResult` and `buildClarifyResult`. Set `messageId`, the current conversation context, and `degraded=false` for normal evidence responses; use `degraded=true` only for explicit fallback paths where the existing result semantics identify a degraded response. Keep transfer/closed responses nullable where no final AI message exists.

Add `kbId/domainCode/userId` to `ConversationInfoVO` and let the existing `BeanUtils.toBean` mapping expose them from history. In `ConversationController.history`, use `SecurityFrameworkUtils.getLoginUserId()` and `conversationService.getConversationForUser`; map a null result to `CONVERSATION_NOT_EXISTS` instead of returning an empty conversation. The existing `MessageService.addMessage` already returns `AiMessageDO`, so no MessageService source change is required.

- [ ] **Step 4: Run the focused backend tests**

```bash
mvn -o -pl yudao-module-chat/yudao-module-chat-server -Dtest=ChatPipelineTest,ConversationServiceTest test
```

Expected: all focused tests pass.

### Task 4: Update the workbench to make the server binding authoritative

**Files:**
- Modify: `/Users/wudi/IdealProjects/myself/yudao-ui-admin-vben-master/apps/web-antd/src/api/ai/chat/index.ts`
- Modify: `/Users/wudi/IdealProjects/myself/yudao-ui-admin-vben-master/apps/web-antd/src/views/ai/workbench-vben/index.vue`

- [ ] **Step 1: Update frontend types before behavior changes**

Extend `AiChatApi.Conversation` with `kbId?: number`, `domainCode?: string`, and `userId?: number`. Extend `SendResp` with `messageId`, `kbId`, `domainCode`, `route`, `intent`, and `degraded`. Change `sendChatMessage` to accept optional `kbId` and retain `kbIds` only as a deprecated compatibility type; the workbench must not populate `kbIds`.

- [ ] **Step 2: Implement history restoration and KB switching**

In `selectConversation`, load history first, then set `currentConversationId`, set `selectedKbId` from `data.conversation.kbId`, and clear `lastResult`. If the returned `kbId` is not in the loaded options, keep the ID and render the domain tag from `conversation.domainCode` rather than silently selecting another KB.

Add a `handleKnowledgeBaseChange` handler. When a current conversation exists and the selected value differs from its bound `kbId`, clear the active conversation/messages/result and leave the selected value as the KB for the next new conversation. Do not mutate or resend the old conversation.

- [ ] **Step 3: Implement new/existing send request construction**

Build the request conditionally:

```ts
const payload = currentConversationId.value
  ? { conversationId: currentConversationId.value, message: text, channel: 'WEB' }
  : { kbId: selectedKbId.value, message: text, channel: 'WEB' };
const resp = await sendChatMessage(payload);
```

Require `selectedKbId` only when `currentConversationId` is absent. After the response, update the conversation ID and display response context without injecting a new KB into an existing conversation.

- [ ] **Step 4: Remove the URL-dependent input lock**

Change the alert to show only for a new conversation with no selected KB, and change the send button condition to:

```vue
:disabled="sending || !draft.trim() || (!currentConversationId && !selectedKbId)"
```

Keep the text area enabled at all times. Show the current conversation’s KB/domain in the card title and history list using the loaded KB options plus the returned `domainCode` fallback.

- [ ] **Step 5: Run frontend static verification**

From the frontend repository:

```bash
pnpm --dir apps/web-antd typecheck
```

Expected: `vue-tsc` exits with code 0 and no type errors. If the workspace script is invoked from `apps/web-antd`, use `pnpm typecheck` instead.

### Task 5: Run integration-level verification and inspect the complete diff

**Files:**
- No new implementation files; inspect all files from Tasks 1–4.

- [ ] **Step 1: Run the complete Chat module test suite**

```bash
mvn -o -pl yudao-module-chat/yudao-module-chat-server test
```

Expected: exit code 0, including existing `ChatPipelineTest` coverage.

- [ ] **Step 2: Compile the affected backend modules**

```bash
mvn -o -pl yudao-module-chat/yudao-module-chat-api,yudao-module-chat/yudao-module-chat-server,yudao-module-knowledge/yudao-module-knowledge-api -DskipTests compile
```

Expected: exit code 0 for the Chat API/server and the Knowledge API dependency used by the new visibility check.

- [ ] **Step 3: Build/typecheck the frontend**

```bash
pnpm --dir apps/web-antd typecheck
pnpm --dir apps/web-antd build
```

Expected: both commands exit 0.

- [ ] **Step 4: Review requirements against the design**

Verify manually from the diff and tests:

1. New conversation stores exactly one `kbId` and its domain snapshot.
2. Subsequent requests cannot overwrite the stored KB with a different request value.
3. Existing conversation history returns `kbId/domainCode` and is restored by the workbench.
4. URL absence of `kbId` does not disable typing or existing-session sends.
5. Cross-user/tenant conversation reads are rejected without leaking details.
6. The workbench never sends `kbIds` for either a new or existing conversation.
7. Documentation files remain unstaged and uncommitted.

- [ ] **Step 5: Inspect repository status**

```bash
git status --short
git diff --check
```

Expected: only intended backend/frontend source and migration changes are present, plus the intentionally uncommitted design/plan documents; no generated build artifacts or unrelated edits are included.
