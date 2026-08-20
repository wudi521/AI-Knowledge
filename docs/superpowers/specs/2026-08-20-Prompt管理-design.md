# 设计文档:Prompt 管理 M6-B(生产级)

> 日期:2026-08-20 · 状态:用户确认(12 处一次全接; 生产级; 领域无关)
> 前置:M6-A 模型网关已交付(本设计复用其模块归属/Feign/降级模式); PRD《M6 AI 运行时·Prompt 管理(版本化/灰度/AB/回滚)》
> 定位:提示词从代码常量收拢到 DB 版本化, 运行期改/灰度/回滚, 换行业/调优不改代码。

---

## 1. 目标与非功能需求

**目标**: 12 处提示词常量 → DB 管理(key/版本/启用/灰度租户), 消费方运行时获取, 失败回退内置默认; 改 prompt 30s 内生效; 回滚=启用旧版本。

**非功能需求**:
| 维度 | 要求 |
|---|---|
| 可用性 | 获取失败/无配置 → 回退代码默认常量(现状行为, 不破坏) |
| 时效 | 管理端启用/灰度变更 ≤30s 生效(model-server 本地缓存 TTL) |
| 版本化 | 每 key 多版本行, 仅 1 个启用; 历史保留, 回滚=启用旧行 |
| 灰度 | 按租户: 启用行 gray_tenant_ids 命中 → 灰度版本 |
| 领域无关 | key 与内容无行业语义; 换行业改配置 |
| 兼容 | 存量 prompt 代码默认保留; RPC 失败降级 |

## 2. 架构

```
消费方(retrieval/evidence/knowledge/eval, 12 处)
  └─ PromptSupport(各模块小工具): promptApi.getPrompt(key, tenantId)
        ├─ 返回非空 → 用 DB prompt
        └─ 返回 null / RPC 异常 → 回退本文件内置默认常量
model-server
  ├─ ai_prompt 表 + PromptService(管理 CRUD/启用/灰度) + /model/prompt/* admin API
  ├─ PromptCache(本地缓存 30s TTL)
  └─ PromptApiImpl: getPrompt(key, tenantId) → 灰度命中? 灰度版本 : 启用版本 (null=无)
```

## 3. 数据模型

### ai_prompt(BaseDO 平台级, 非租户——prompt 是平台配置)

| 列 | 说明 |
|---|---|
| prompt_key | varchar(64) 业务键(如 query-analysis/slot-detect/answer-generate/claim-verify/conflict-detect/slot-summarize/intent-summarize/conflict-rule/review-extract/eval-case-generate/search-answer/rerank-llm/query-disambiguate…) |
| name / description | 名称/说明 |
| content | text 提示词内容 |
| version | int 版本号(同 key 内自增) |
| status | 0=停用 1=启用(每 key 至多 1 个启用) |
| gray_tenant_ids | varchar(500) JSON 数组(启用行的灰度租户列表; 空=不灰度) |

## 4. 接口

- `PromptApi.getPrompt(key, tenantId)` → CommonResult<String>(Feign; null=该 key 无启用配置, 调用方回退默认)
- admin(权限 `model:prompt:*`):
  - POST /model/prompt/create(key/name/description/content) → 新版本行(同 key 最大版本+1, status=0 草稿)
  - PUT /model/prompt/update(id/content/name/description) → 改未启用行
  - POST /model/prompt/enable?id= → 启用该行, 同 key 其他启用行自动停用(灰度保留)
  - PUT /model/prompt/gray{id, tenantIds} → 设置启用行灰度租户
  - GET /model/prompt/page / get / key-list(所有 key + 当前启用版本摘要)

## 5. 消费方接入(12 处, 全部)

| 模块 | key | 位置 |
|---|---|---|
| retrieval | query-analysis / query-disambiguate | QueryAnalysisService(2) |
| retrieval | search-answer | SearchService |
| retrieval | rerank-llm | Reranker |
| evidence | slot-detect | SlotDetector |
| evidence | answer-generate | AnswerGenerator |
| evidence | claim-verify | ClaimVerifier |
| evidence | conflict-detect | ConflictDetector |
| knowledge | review-extract | ReviewItemServiceImpl(2) |
| knowledge | slot-summarize | SlotSummarizer |
| knowledge | intent-summarize | IntentSummarizer |
| knowledge | conflict-rule | ConflictServiceImpl |
| eval | eval-case-generate | EvalCaseService |

每个消费模块:
- 新增 `service/prompt/PromptSupport`(注入 PromptApi + SecurityFrameworkUtils 取租户): `String get(String key, String defaultPrompt)` — RPC 失败/空回退默认
- 原 `SYSTEM_PROMPT` 常量保留为 defaultPrompt 参数
- 各模块 RpcConfiguration 注册 `PromptApi`(model-api 依赖已有: knowledge/evidence/eval 已依赖 model-api? 检查——retrieval 有 model-api; knowledge 有; evidence 有; eval 刚加了 model-api; 需确认各模块 pom + Feign)

## 6. 灰度与缓存

- getPrompt(key, tenantId): 启用行 gray_tenant_ids 含 tenantId → 返回灰度行? 否——MVP 语义: 灰度 = **启用行本身带灰度租户列表**, 命中的租户用"该行内容"…… 简化正确定义: 每 key 至多 1 个启用行; 灰度租户列表挂在启用行上表示"该行内容对灰度租户生效"——但这样非灰度租户用什么? 需要"启用行(全量)" + "灰度行(部分租户)"两个活动版本。
- **修正灰度模型**: 每 key 活动状态: `enabled_version`(全量启用行, status=1) + 可选 `gray_version`(status=2 灰度中, 带 gray_tenant_ids)。getPrompt: tenantId ∈ gray_tenant_ids → gray_version 内容; 否则 enabled_version 内容。
- 管理: enable(id, 全量)/ gray-enable(id, tenantIds)(设该行为灰度行, 同 key 至多 1 个灰度)/ gray-off(id)(取消灰度)
- 缓存: ConcurrentHashMap<key, CacheEntry{enabledContent, grayTenantIds, grayContent, expireAt}>, TTL 30s

## 7. 验证(PM-01~05, 非售后锚点)

| 编号 | 场景 | 步骤/期望 |
|---|---|---|
| PM-01 | 生效 | 改 slot-detect prompt(如加一句规则) → 30s 内槽位抽取行为变化 |
| PM-02 | 回滚 | enable 旧版本 → 恢复旧行为 |
| PM-03 | 灰度 | 租户 A 加灰度版本(不同内容) → 租户 A 用新版, 租户 B 用全量版 |
| PM-04 | 降级 | 无配置 key → 消费方回退代码默认, 链路正常 |
| PM-05 | 通用 | 改 query-analysis prompt 后物流/公司文档库检索链路仍通 |

## 8. 文件清单

- model-api: PromptApi + DTO; model-server: ai_prompt DO/Mapper/Service/Controller/VO + PromptCache + PromptApiImpl
- 消费方 4 模块: PromptSupport + 12 处替换 + RpcConfiguration 注册(如需)
- DDL: ai_prompt 建表
