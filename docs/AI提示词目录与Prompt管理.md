# AI 提示词目录与 Prompt 管理（M6-B）

> 2026-08-21 全量枚举 + 种子灌库：系统内所有用到 LLM 的提示词统一纳入 `ai_prompt` 管理，
> 管理页（④ AI 运行时 → 提示词管理, 6812）从"空摆设"变为"真管理"。

## 一、全量目录（14 个 LLM 提示词场景）

全系统共 14 个语义场景，分布在 4 个消费模块 + 1 个工具模块。每个场景 = 1 个 `prompt_key`，
当前均为 **version=1, status=1(启用全量)**, 内容 = 代码内置默认（种子数据 `sql/migrate-20260821-prompt-seed.sql`）。

| # | prompt_key | 名称 | 模块 | 调用点 | 占位符 |
|---|---|---|---|---|---|
| 1 | `query-analysis` | 查询分析(固定意图) | retrieval | QueryAnalysisService | - |
| 2 | `query-disambiguate` | 查询分析(知识库意图集) | retrieval | QueryAnalysisService | `__INTENT_LIST__`(构建时替换) |
| 3 | `search-answer` | 检索直答 | retrieval | SearchService | - |
| 4 | `rerank-llm` | LLM 重排打分 | retrieval | Reranker | - |
| 5 | `slot-detect` | 槽位检测 | evidence | SlotDetector | `{defs}`(运行时替换) |
| 6 | `answer-generate` | 证据答案生成 | evidence | AnswerGenerator | - |
| 7 | `claim-verify` | 断言证据核查 | evidence | ClaimVerifier | - |
| 8 | `conflict-detect` | 证据对冲突检测 | evidence | ConflictDetector | - |
| 9 | `review-extract` | 知识条目抽取 | knowledge | ReviewItemServiceImpl#extractBatch | - |
| 10 | `review-product` | 文档产品识别 | knowledge | ReviewItemServiceImpl#extractAndStoreProducts | - |
| 11 | `conflict-rule` | 条款新旧冲突审查 | knowledge | ConflictServiceImpl | - |
| 12 | `slot-summarize` | 槽位自动生成 | knowledge | SlotSummarizer | - |
| 13 | `intent-summarize` | 意图自动生成 | knowledge | IntentSummarizer | - |
| 14 | `eval-case-generate` | 评测考题生成 | eval | EvalCaseService | - |

> 注：`rerank-llm` 仅在 LLM 重排模式生效；bge-reranker(向量重排)不走 chat，无提示词。
> 其他所有 `modelApi.chat` 调用点（含重试）均已接入 `PromptSupport`，无遗漏。

## 二、消费链路

```
各消费模块 service/xxx/prompt/PromptSupport.get(key, codeDefault)
   → Feign PromptApi.getPrompt(key, tenantId)
   → model-server GET /admin-api/model/prompt/get-prompt?key=&tenantId=
   → PromptApiImpl: ai_prompt 查 status in (1,2) → 灰度命中(2) ? grayContent : enabledContent
   → PromptCache 30s TTL
   → 空/异常 → 回退调用点代码内置默认(永不阻断业务)
```

- 灰度：`status=2` + `gray_tenant_ids` JSON 数组；命中租户用灰度版，其余用全量版。
- 版本：同 key 新版本默认 `status=0`(停用)；`enable` 切换后同 key 其他启用行自动停用（全量启用唯一）。
- 编辑约束：**仅停用版本可编辑**（防止运行中被直接改），改 = create 新版本 → enable 或 gray-enable。
- 管理 API：`/model/prompt/{create,update,enable,gray-enable,gray-off,get,page,delete,key-list}`。
- 权限：`model:prompt:{query,create,update,delete}`（6813-6816）。

## 三、本次变更（2026-08-21）

1. **全量枚举**：审计全系统 `modelApi.chat` 调用点 → 14 场景目录（上表）。
2. **拆 key**：原 `review-extract` 一个 key 被两个不同语义共用（条目抽取 + 产品识别），
   拆为 `review-extract`(条目抽取) 与 `review-product`(产品识别) 两个独立 key，
   ReviewItemServiceImpl 已改（需重启 knowledge-server 生效）。
3. **种子灌库**：`sql/migrate-20260821-prompt-seed.sql` 灌入 14 行启用版 v1（内容 = 代码内置默认），
   已执行，并清理了此前 PM 验证残留的 20 行测试数据。
4. **验证**：14 个 key get-prompt 全部返回真实内容（len 与代码默认一致）；编辑闭环
   create v2 → enable → 缓存过期后 get 到新内容 → 恢复 v1 全部通过。

## 四、运维说明

- 想改某个提示词：管理页新建（同 key）→ 编辑内容 → 启用 或 灰度设置；不重启服务即可生效（30s 缓存）。
- 回滚：把旧版本内容再建为新版本启用即可。
- 新增提示词场景：调用点用 `promptSupport.get("new-key", 代码默认)`，管理页新建同 key 行即可接管；
  若新建 key 已启用，运行即用 DB 内容（无需重启，30s 内）。
- 删除 `ai_prompt` 启用行后，运行时回退代码默认（degrade-never-block）。
