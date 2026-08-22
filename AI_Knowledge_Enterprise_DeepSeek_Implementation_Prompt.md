# 企业级 AI 知识库改造总控实施指令

## 1. 你的角色与最终目标

你现在是本项目的主程，角色同时包含：Java 17 / Spring Cloud 架构师、RAG 检索工程师、数据一致性工程师、安全工程师和测试负责人。

请直接在当前仓库中实施改造，不要只给建议、伪代码、TODO 或空壳类。目标是将现有项目从“功能较完整的 RAG 平台”演进为可上线、可审计、可扩展、可恢复的企业知识智能平台。

仓库现有技术方向必须尽量保留：

- Java 17、Spring Boot / Spring Cloud、MyBatis-Plus、MySQL、Kafka、Elasticsearch、Milvus、Redis、XXL-Job；
- 现有模块边界：ingestion、knowledge、retrieval、evidence、eval、model、rule、agent、chat、governance、workflow；
- 现有租户机制、统一返回体、异常体系、日志规范、DO/Mapper/Service/Controller 分层和 Feign API 风格。

禁止推倒重写，禁止把整个项目替换为 LangChain、Dify、FastGPT 等外部框架，禁止为了“看起来先进”一次性引入大量基础设施。

## 2. 开始修改前必须完成的动作

1. 先执行 `git status --short`，不得覆盖、删除或重置用户未提交的修改，不得执行 `git reset --hard`。
2. 阅读根目录的 `CONVENTIONS.md`、`CLAUDE.md`、README 和 `docs` 下现有审查文档。
3. 核对并记录以下关键代码的现状和调用链，记录到 `docs/enterprise-upgrade/00-baseline.md`，但记录后要继续实施，不能停在分析阶段：
   - `ParentChildSplitter.java`
   - `IngestServiceImpl.java`
   - `IngestionApiImpl.java`
   - `AiDocVersionServiceImpl.java`
   - `EsChunkStore.java`
   - `MilvusChunkStore.java`
   - `SearchService.java`
   - `ResultFilter.java`
   - `VectorSearcher.java`
   - `Bm25Searcher.java`
   - 各 AI 模块的 `SecurityConfiguration.java`
   - `LoginUserRequestInterceptor.java`
   - `TokenAuthenticationFilter.java`
   - `AiModelConfigDO`、`AiModelConfigServiceImpl`
   - knowledge / ingestion / evidence / eval / model / agent / governance 的 SQL 文件。
4. 先运行当前相关模块的基线编译和测试，保留结果。至少运行：

```bash
mvn -pl \
yudao-module-ingestion/yudao-module-ingestion-server,\
yudao-module-knowledge/yudao-module-knowledge-server,\
yudao-module-retrieval/yudao-module-retrieval-server,\
yudao-module-evidence/yudao-module-evidence-server,\
yudao-module-eval/yudao-module-eval-server,\
yudao-module-model/yudao-module-model-server \
-am test
```

如果环境缺少 MySQL、Kafka、ES、Milvus 等外部依赖，必须明确区分“编译通过、单元测试通过、集成测试未运行”，不能把未运行写成通过。

## 3. 已知高风险点：先验证，再按下面方案修复

请逐项核对源码，确认后实施，不要照抄结论而不看代码：

1. `ParentChildSplitter` 使用递增序号作为 `parentId`，而 `ai_chunk.id` 是数据库生成的真实主键，父子关系可能断裂；检索链路也没有真正执行 Child Hit → Parent Expansion。
2. 入库流程一次性对全部 chunk 调用 embedding，并逐条插入 MySQL；大文档缺少分批、断点、幂等和任务状态。
3. 发布流程在数据库事务中同步调用 ES / Milvus；ES 成功、Milvus 失败或数据库回滚时会形成跨系统不一致。
4. ES / Milvus 当前索引字段不足，缺少 `document_id`、`version_id`、有效期、父子角色、权限、安全级别、地域和业务范围等关键过滤字段。
5. 新版本发布和旧版本过期没有形成 Version → Chunk → ES → Milvus 的完整失效链；检索主要按 chunk 状态判断，可能出现旧版本、提前可见或短暂错误数据。
6. 文档创建后直接异步发送 Kafka，缺少 Outbox；消费端缺少持久化幂等任务，消息丢失或重复时无法可靠恢复。
7. `/rpc-api/**`、Actuator、Druid 等路径存在过度放行；`login-user` 请求头在框架层被直接解析，源码内没有看到足够的服务身份签名和外部请求头剥离保障。
8. 模型 API Key 虽然响应脱敏，但数据库字段仍是明文，缺少专用密钥加密、轮换和版本管理。
9. ingestion 根据数据库中的 `storagePath` 直接发起 HTTP 下载，存在 SSRF、重定向、超大文件、伪造文件类型和压缩炸弹风险。
10. AI 模块自动化测试覆盖非常薄弱，SQL 仍以基线脚本中混合 `CREATE TABLE` 和不可重复 `ALTER TABLE` 为主，缺少正式版本化迁移。
11. agent、workflow、governance 目前存在占位接口或固定返回值，不能作为生产能力对外宣称。
12. `ai_chunk.embedding` 使用 JSON 文本保存高维向量；在千万级 chunk 场景下会严重放大 MySQL 容量、IO、备份和迁移成本。

## 4. 不可违反的工程原则

1. MySQL 是业务状态和治理数据的事实源；ES、Milvus、Neo4j 都只能是可重建索引或派生视图。
2. 数据库事务中禁止执行远程模型调用、HTTP 下载、Feign 远程写、ES 写、Milvus 写或其他不可回滚副作用。
3. 所有异步操作必须具备：幂等键、持久化状态、重试次数、指数退避、死信/人工恢复、可观测 traceId。
4. 权限必须 Fail Closed。权限服务、租户上下文或 ACL 计算失败时返回空或拒绝，不能降级为“查全部”。
5. 任何“发布成功”都必须代表数据库状态、索引状态和评测闸门达到定义好的完成条件，不能只代表接口没有抛异常。
6. 数据库改造必须使用版本化迁移；不得把新的无条件 `ALTER TABLE` 继续追加到原始基线 SQL。
7. 所有新增表和唯一约束必须包含租户维度；所有查询都要验证租户条件。
8. 不允许使用 Redis 作为知识、版本、权限或任务最终状态的唯一事实源；Redis 只用于缓存、锁、nonce、防重和短期状态。
9. 不修改 `target`、生成文件或无关业务模块，不做全仓库格式化。
10. 任何批次未完成时，不得用 TODO、空实现、`return false`、`return true` 或固定值冒充完成。

## 5. 目标架构

```text
Source / Connector
        ↓
Document → DocumentVersion → Section → Chunk
        ↓                        ↓
Ingestion Job / Task       Entity / Relation / Statement
        ↓                        ↓
Embedding Artifact         Knowledge Layer
        ↓                        ↓
Index Job / Task ─────→ ES / Milvus / Graph Index
        ↓
Review → Evaluation Gate → Publish State Machine
        ↓
Query Planner
  ├─ Hybrid RAG
  ├─ Entity Lookup
  ├─ Graph Traversal
  ├─ Rule Engine
  ├─ Read-only SQL
  └─ Approved External API
        ↓
Permission + Scope + Version + Temporal Filter
        ↓
Evidence → Claim Verification → Citation → Answer / Abstain
        ↓
Trace / Evaluation / Audit / Cost / Governance
```

## 6. 分批实施顺序

按 A → B → C → D → E → F 顺序实施。每完成一批，必须先编译、测试、列出数据库迁移和回滚办法，再进入下一批。上下文或执行时间不足时，完整完成当前批次后停止，不能留下半批次代码。

---

# 批次 A：安全边界、密钥和迁移基线

## A1. 服务间 RPC 身份认证

在公共 security / rpc framework 中实现统一的内部服务认证，避免每个模块复制代码。

推荐实现短时 HMAC 签名：

- 请求头：`X-Internal-App`、`X-Internal-Timestamp`、`X-Internal-Nonce`、`X-Internal-Signature`；
- 签名内容至少包含：HTTP method、规范化 path、timestamp、nonce、body SHA-256、调用方 appId；
- HMAC-SHA256 密钥来自环境变量或 Nacos，不得写入仓库；
- 时间窗口默认 300 秒；
- nonce 使用 Redis `SET NX EX` 防重放；Redis 不可用时内部 RPC 默认拒绝，而不是放行；
- Feign 统一拦截器负责签名；Servlet Filter 负责验签；
- `login-user` 只作为“已通过内部认证后的用户上下文”使用，外部直接携带该头必须被网关和业务服务剥离或拒绝；
- 对调用方 appId 建立 allowlist，并记录调用服务、目标服务、路径、traceId 和验签结果；
- 将各模块 `ApiConstants.PREFIX + "/**"` 从无条件 `permitAll` 改为仅通过内部认证可访问。

Actuator 仅开放最小的 liveness/readiness；其他 endpoint 需要管理权限或内网认证。生产环境禁用或保护 Druid 控制台、Swagger 和敏感 env/config endpoint。

验收用例：伪造 `login-user` 头、过期 timestamp、重复 nonce、错误签名、未登记 appId 都必须被拒绝；合法 Feign 调用正常通过。

## A2. 模型密钥保护

新增 `SecretCryptoService` 抽象，默认实现 AES-256-GCM，预留 KMS/Vault 实现：

- 主密钥只从环境变量、K8s Secret 或 Nacos 加密配置读取；
- 每条密钥随机 nonce，保存 ciphertext、nonce、keyVersion；
- 新增数据库字段后采用“双读单写”：优先读取密文，过渡期允许旧明文字段；所有新写只写密文；
- 提供一次性迁移命令或受保护的管理 Job，把旧明文加密后清空；
- API 响应永远不返回明文；更新请求收到 `****` 等脱敏占位符时表示“不修改”；
- 日志、异常、审计和 `toString()` 不得输出密钥；
- ModelInvoker 只在调用瞬间解密，解密结果不缓存到可序列化对象；
- 增加密钥轮换和 keyVersion 兼容。

## A3. 文件下载与上传安全

停止把任意 URL 当作可信 MinIO 地址直接下载。

优先方案：

- `ai_document` 保存 `file_id`、storage config id、object key、原始文件名、MIME、size、SHA-256，而不是只保存可访问 URL；
- 扩展 infra `FileApi`，按受控 `fileId` / object key 流式读取，ingestion 不直接访问任意网络 URL；
- 限制最大文件大小、解析后页数、总像素、压缩后展开比、单文档最大 chunk 数和解析超时；
- 通过文件 magic number 校验真实类型，不能只信扩展名和 Content-Type；
- 临时文件使用受限权限，完成后可靠清理；
- 预留病毒扫描接口和隔离状态。

兼容旧 `storagePath` 时，必须限制 scheme、host、port、DNS 解析结果、重定向次数，并拒绝 localhost、内网段、云元数据地址及 DNS rebinding；最终逐步下线 URL 读取路径。

## A4. 正式数据库迁移

先检查项目是否已有 Flyway/Liquibase。若没有，接入 Flyway，并明确每个 schema 的 migration owner，避免多服务随意执行基线 SQL。

要求：

- 新迁移放入版本化目录，名称清晰、可重复部署、不可破坏已有数据；
- 原 `knowledge.sql` 等保留为“全新环境基线”，后续变更不再追加无条件 ALTER；
- 为每次迁移提供前置检查、数据回填、回滚/降级说明；
- 大表加索引和回填要分阶段，避免长时间锁表；
- 增加唯一约束和组合索引前先检查并清理重复数据。

---

# 批次 B：修复 Parent-Child、结构切片和可追溯元数据

## B1. ai_chunk 数据模型

在不破坏现有字段的前提下，为 `ai_chunk` 增加或完善：

- `chunk_key`：版本内稳定、幂等的业务键；
- `chunk_seq`：文档/章节中的顺序；
- `chunk_role`：`PARENT`、`CHILD`、`LEAF`、`TABLE`、`IMAGE`；
- `parent_id`、必要时 `root_id`；
- `section_path`、heading level；
- `source_page_start`、`source_page_end`；
- `source_offset_start`、`source_offset_end`；
- `token_count`、`content_hash`、`language`；
- `prev_chunk_id` / `next_chunk_id` 可通过顺序派生，不强制物理列；
- `index_status`、`embedding_model_id`、`embedding_version`、`embedding_hash`；
- `security_level` 和可索引的业务 scope 摘要。

至少增加：

- 唯一约束 `(tenant_id, version_id, chunk_key)`；
- 索引 `(tenant_id, version_id, status)`；
- 索引 `(tenant_id, parent_id)`；
- 必要的文档/版本查询索引。

## B2. Parent-Child 正确落库

修改 `Chunk` 和 `ParentChildSplitter`：

- splitter 生成 `localKey` 和 `parentLocalKey`，不能把序号直接当数据库主键；
- localKey 必须在同一版本、同一输入内容和同一切分配置下稳定；
- 父块用于上下文，子块用于精确召回；未超长段落使用 `LEAF`，不要重复生成同内容的父块和子块；
- 超长父块必须有最大上下文限制，必要时形成层级父块而不是无限大文本。

修改持久化：

1. 批量落父块/叶子块；
2. 获取 `localKey → DB id` 映射；
3. 批量落子块并写入真实 `parent_id`；
4. 全流程基于 `(tenant_id, version_id, chunk_key)` 幂等 upsert 或先建立新的 generation，再切换；
5. 禁止逐 chunk 单条 `insertChunks(List.of(...))`。

## B3. 检索扩展闭环

修改 retrieval：

- ES/Milvus 只索引 `CHILD`、`LEAF` 等可召回块，父块是否索引必须有明确策略；
- 召回、融合、权限/版本过滤、rerank 后，根据命中的 child 批量取 parent；
- 返回结果同时保留 `matchedChunkId/matchedContent` 和 `contextChunkId/contextContent`；
- citation 必须锚定命中的精确 child 或原始 source locator，parent 只能作为上下文，不能伪造精确引用；
- 同一 parent 多个 child 命中时去重，并保留最高分 child；
- 加入 context token budget，避免多个大 parent 撑爆模型上下文；
- 支持按 `chunk_seq` 取相邻窗口，解决跨段落语义依赖。

验收用例：任何 child 的 `parent_id` 都必须在当前租户/版本中真实存在；不得出现 `1、2、3` 这类伪主键；命中 child 时能回带正确父上下文；不同版本不能串 parent。

---

# 批次 C：入库 DAG、Outbox、索引任务和发布状态机

## C1. 持久化任务模型

新增或复用以下表；若已有同义表则扩展，不能重复造概念：

- `ai_outbox_event`
- `ai_ingestion_job`
- `ai_ingestion_task`
- `ai_index_job`
- `ai_index_task`
- `ai_index_reconcile_run` 或同义的一致性巡检记录。

关键字段至少包括：tenantId、documentId、versionId、jobType、stage、target、operation、status、idempotencyKey、payloadHash、total/progress、retryCount、maxRetry、nextRetryTime、leaseOwner、leaseExpireAt、errorCode、errorMessage、traceId、startedAt、finishedAt、optimisticVersion。

唯一约束至少保证同一租户、版本、任务类型和 payload 不会重复创建有效任务。

## C2. Outbox 与消息幂等

修改文档创建：

- 文档、DRAFT 版本、Outbox 事件在同一 MySQL 事务中提交；
- 独立 Outbox Publisher 可靠发送 Kafka，并在成功后更新事件状态；
- 消费端先用幂等键创建/获取 ingestion job，再执行；重复消息不得重复删除和插入数据；
- DLQ 必须有管理查询和重放能力，不能只写日志。

## C3. 入库 DAG 和分批处理

将同步大流水线拆为可恢复阶段：

```text
FETCH → VALIDATE → PARSE → STRUCTURE → CHUNK
      → EMBED(batch) → PERSIST → REVIEW_PREPARE → DONE
```

要求：

- 每个 stage 单独记录状态、耗时和错误；
- embedding 批大小配置化，默认可从 32/64 起步；
- 校验模型返回数量、向量维度、空向量、NaN/Infinity；
- 限制并发、速率和内存，支持 backpressure；
- 成功批次可断点续跑，失败后不重复计算已完成批次；
- chunk 内容未变化且 embedding model/version 相同，可按 content hash 复用向量；
- 大文档不能一次把全部文本和向量常驻 JVM 内存。

## C4. 向量持久化策略

停止长期把高维向量以 JSON TEXT 大量保存在 `ai_chunk`：

- `ai_chunk` 仅长期保存 embedding 的模型、版本、hash、状态；
- 索引任务所需向量可临时以 float32 二进制 BLOB 或对象存储 artifact 保存，并配置 TTL；
- 索引成功后按策略删除临时 artifact；
- 需要重建时优先按内容和模型版本重新生成，或从受控 artifact 恢复；
- 迁移期保留旧字段双读，但新流程不再写 JSON 向量。

## C5. 发布状态机与两阶段索引

重构 `AiDocVersionServiceImpl.publish`：数据库事务内不得直接调用 `ingestionApi.indexVersion`。

推荐流程：

1. 发布请求在短事务中完成权限、审核、冲突和评测校验；
2. 对 document 行加适当锁或使用乐观锁，防止并发发布；
3. version 保持 REVIEW，设置独立 `index_status=INDEXING` / `publish_request_id`，创建 IndexJob 和 Outbox；
4. worker 将新版本写入 ES/Milvus 的 `STAGING` generation；
5. ES、Milvus 全部写入并校验成功后，在短事务中把新版本置 PUBLISHED、旧版本置 EXPIRED、设置 effective time，并更新文档的 publishedVersionId；
6. 再异步激活新索引、失活旧索引；检索端在过渡期仍必须做数据库版本状态的最终防线；
7. 任一索引失败时，新版本不得对用户可见，旧 PUBLISHED 版本继续服务；
8. 删除采用 tombstone/DELETING 状态和可重试清理任务，不能先删 MySQL 后对 ES/Milvus“尽力而为”。

## C6. ES / Milvus v2 索引

不要原地假设旧 schema 自动升级。

- ES 使用版本化 index + read/write alias；
- Milvus 新建 v2 collection，支持 backfill、校验、双写和读开关切换；
- 切换成功后再下线旧索引；
- 不要为每个租户或每个知识库单独建 collection。

索引字段至少包含：

- tenant_id、kb_id、document_id、version_id、chunk_id、parent_id、chunk_role；
- status、index_generation、effective_from、effective_to；
- language、content_hash、security_level；
- region/province/city、product、channel、customer segment 等业务 scope；
- ACL 过滤所需字段或经过验证的可扩展方案。

必须修复：

- ES bulk HTTP 200 但 item 失败的场景，解析 `errors` 和每个 item；
- Milvus 写入数量、维度、状态和 flush/可见性校验；
- 空版本不能被当成索引成功；
- 写入、激活、失活和删除任务都要幂等；
- 定时 Reconcile 对比 MySQL 事实源与 ES/Milvus 的数量、ID/hash，并支持修复。

---

# 批次 D：分层 ACL、地域/产品硬过滤和 Query Planner v1

## D1. 企业级 ACL

新增统一资源 ACL 模型，例如 `ai_resource_acl`：

- resourceType：KB、FOLDER、DOCUMENT、CHUNK、ENTITY；
- resourceId；
- subjectType：USER、ROLE、DEPT、ORG、ALL；
- subjectId；
- action：READ、WRITE、REVIEW、PUBLISH、ADMIN；
- effect：ALLOW、DENY；
- inherit、effectiveFrom、effectiveTo；
- tenantId、审计字段。

规则：

- DENY 优先于 ALLOW；
- 文档继承文件夹/知识库 ACL，但允许显式覆盖；
- 权限失败必须返回空或拒绝；
- 检索必须在 BM25 / vector 召回前做可执行的权限预过滤，最终响应前再做一次防御性校验；
- 禁止把所有 ACL 都放到召回后逐条 RPC 判断；
- `visible_roles` 逗号字符串只能作为迁移兼容，不能作为最终 ACL 模型；
- 超管绕过必须明确、可审计，不能用“无登录态即直通”代替内部服务身份。

## D2. 地域、产品、渠道和时间范围

为“同一套餐在不同省市规则不同”的场景建立一等 scope 模型，例如 `ai_knowledge_scope`，并把可检索字段反范式写入索引：

- provinceCode、cityCode、districtCode；
- productCode / planCode；
- channel；
- customerSegment；
- organization / department；
- authorityLevel、scopePriority；
- effectiveFrom / effectiveTo。

Query Analysis 必须抽取这些 slot。已知城市时必须硬过滤，不能仅靠向量相似度；缺少关键 slot 且结果会产生歧义时，应要求补充信息或返回多范围对比，不能混合不同地市规则后总结。

实现可配置的规则优先级，例如：精确城市 > 省级 > 全国；精确产品/渠道 > 通用规则；有效期内且权威级别更高的知识优先。冲突无法自动裁决时必须标记冲突或拒答。

## D3. Query Planner v1

新增可版本化的 `QueryPlan`，至少支持：

- `HYBRID_RAG`
- `ENTITY_LOOKUP`
- `GRAPH_TRAVERSAL`
- `RULE`
- `READ_ONLY_SQL`
- `EXTERNAL_API`
- `COMPOSITE`

Planner 输出必须通过 JSON Schema/Java DTO 严格校验，包含：route、subQuestions、filters、asOfTime、requiredSlots、tools、confidence、reasonCode。LLM 失败时使用规则安全降级，不允许生成任意 SQL 或 URL。

第一版先实现：

- 普通问答 → Hybrid RAG；
- 明确实体属性 → Entity Lookup；
- 有地域/产品/时间 slot → Scope Filter + Hybrid RAG；
- 超范围/证据不足 → Abstain 或转人工。

检索变体应受控并行执行，设置总超时、单通道超时和并发上限；配置 recallTopK、rerankTopK，而不是写死。加入 query embedding、意图和 ACL 的版本化缓存，但缓存 key 必须包含租户、权限版本、模型版本和过滤条件。

---

# 批次 E：Knowledge Layer、实体消歧、关系和多跳推理

先在 MySQL 建立正确的一等知识模型，数据稳定后再接 Neo4j。MySQL 是事实源，Graph DB 是派生索引。

至少新增/完善：

- `ai_entity_type`
- `ai_entity`
- `ai_entity_alias`
- `ai_entity_mention`
- `ai_relation_type`
- `ai_relation`
- `ai_relation_evidence`
- `ai_knowledge_statement`
- 实体 merge/split 审计和人工复核记录。

关键要求：

- Entity 包含 tenantId、kbId、type、canonicalName、normalizedName、attributes、status、confidence；
- Alias 和 Mention 必须保留原始文本、chunkId、versionId、source locator、模型/规则来源和置信度；
- Relation 包含 subject、predicate、object/value、validFrom、validTo、authority、confidence；
- 每条 relation/statement 必须能追到文档版本和证据 chunk；
- Entity Resolution 先做规范化 + 精确 alias + 业务规则，再做向量/LLM 候选；低置信度不能自动合并；
- 合并和拆分可撤销、可审计；不同租户绝不跨域合并；
- ingestion 中的实体/关系抽取作为异步 task，输出必须结构化校验和幂等；
- Conflict 不再只依赖相似文本，而要比较 Subject-Predicate-Object、作用范围、有效期和权威级别。

新增 `GraphRepository` 抽象。第一阶段可以用 MySQL 支持有限 1~2 hop；实体/关系质量和评测稳定后，再实现 Neo4j adapter、Graph Retrieval 和 GraphRAG。禁止一开始把所有 chunk 粗暴塞进 Neo4j。

验收示例：

- “小张、张三、张工”能被解析为同一实体或进入人工消歧；
- “小张的上级的上级是谁”能执行 2-hop，并返回每一跳的证据和有效期；
- “小张去年换过几个领导”按 temporal relation 计算，而不是普通文本猜测。

---

# 批次 F：Evidence、Agent Runtime、评测、观测与运维

## F1. Evidence Lineage

Evidence 不能只保存 chunkId。建立或完善：

- `ai_answer_claim`
- `ai_answer_citation`
- `ai_evidence` / evidence snapshot；
- page、paragraph、offset、bounding box、sheet、table/row/cell 等 source locator；
- documentId、versionId、chunkId、原文件 hash、source URI/object key、权限和权威级别；
- claim 与 evidence 的支持/反驳/不足关系。

回答生成后逐 claim 验证；证据不足、权限不允许、冲突未裁决或引用无法复现时返回 Abstain，不允许硬答。父块可提供上下文，但引用必须落到精确来源。

## F2. Agent Runtime

当前占位 Agent/Workflow/Governance 不能直接进入生产路径。实现时至少需要：

- Planner、Tool Registry、Executor、State、Approval、Budget、Guardrail；
- Tool schema 严格校验，工具 URL/API 只能来自 allowlist；
- READ 工具和 WRITE 工具分级，写操作必须人工审批或明确策略；
- SQL 工具只允许只读视图，自动注入 tenant 条件，禁止 DDL/DML，设置超时、行数和成本上限；
- 每次 tool call 有 idempotency key、输入输出摘要、审批人、结果、traceId；
- 防止 prompt injection 让模型绕过工具权限；文档内容始终被视为不可信数据，不是系统指令。

若本轮不实施完整 Runtime，应把这些模块标记为 EXPERIMENTAL，不得用固定返回值伪装可用。

## F3. Model Gateway

在现有 gateway 上增加：

- 按场景路由：intent/entity/chunk summary 用小模型，普通回答用中模型，复杂推理/冲突用大模型，vision/rerank/embedding 独立；
- prompt/template/model 配置版本化；
- provider 级限流、bulkhead、超时、重试分类、成本预算；
- 本地熔断 + 集中 provider health 状态；
- 401/403 等永久错误与 429/5xx 瞬时错误分开处理；
- 租户配额和成本告警。

## F4. Evaluation Gate

将现有 Eval 变成持续回归闸门，按类型分别统计：

- Simple QA、Semantic、Keyword、Entity、Multi-hop、Temporal、Conflict、Permission、Scope、Rule、Abstention、Citation；
- Recall@K、MRR、NDCG、Faithfulness、Citation Accuracy、Abstention Accuracy、权限泄露率；
- 代码、prompt、模型、切片、索引 schema、知识版本变化都触发回归；
- 不能只看总平均分；关键安全类别必须零泄露；
- 评测未通过不能发布新配置或知识版本。

## F5. Observability 与审计

新增/完善：

- `ai_query_trace`
- `ai_query_plan`
- `ai_retrieval_trace`
- ingestion/index stage trace；
- OpenTelemetry 跨 HTTP、Feign、Kafka 传递 traceId；
- 记录各阶段耗时、候选数、过滤原因、模型/Prompt 版本、token 和成本；
- 对问题、文档和模型输入做脱敏/摘要，禁止把敏感原文无条件写日志；
- 指标至少包含 p50/p95/p99、失败率、索引延迟、任务积压、DLQ、模型错误、ACL 拒绝、无答案率、citation rate、成本。

## F6. 企业运维

补齐：

- 每租户的文档、chunk、QPS、并发、token、存储配额；
- 数据保留、软删除、彻底擦除、legal hold；
- MySQL、ES、Milvus、对象存储的备份与恢复演练；
- RPO/RTO 文档；
- ES alias、Milvus collection 的滚动升级和回滚；
- SBOM、依赖漏洞扫描、镜像非 root、TLS、Secret 管理；
- 管理 API：任务查询/重试、DLQ 重放、索引一致性、ACL、实体合并、评测、模型健康、成本。

当前仓库若没有前端工程，不要伪造前端已完成；只实现后端 API、OpenAPI 契约和单独的前端任务清单。

## 7. 必须增加的测试矩阵

至少覆盖以下场景：

1. child.parentId 是真实数据库父主键，跨版本不串联；
2. 同一 ingestion Kafka 消息重复消费不会重复生成 chunk；
3. 文档写库成功但 Kafka 发送失败时，Outbox 能补发；
4. ES 成功、Milvus 失败时，新版本不会 PUBLISHED，旧版本继续可检索；
5. 两个并发发布请求只有一个成功进入有效状态；
6. EXPIRED、未生效、已过期版本不能出现在当前查询；asOfTime 查询可命中正确历史版本；
7. 租户 A、角色 A 无法看到租户 B 或 DENY 文档；权限系统失败时不泄露；
8. 城市已知时不会混入其他城市同名套餐；缺关键范围时触发澄清/拒答；
9. ES bulk item 失败能识别并重试；Milvus 维度错误被阻断；
10. 模型 API Key 数据库中无明文、接口无明文、日志无明文；
11. 伪造 login-user、重放内部签名、未登记服务调用均被拒绝；
12. 恶意 URL、内网地址、重定向、超大文件、伪 MIME 和压缩炸弹被拒绝；
13. Evidence 能从 claim 追到原始文档版本、页码/位置；
14. 删除后立即不可检索，外部索引失败时清理任务可恢复；
15. Entity merge/split 可审计、可回滚；多跳结果有逐跳证据。

单元测试、Mapper/Service 测试、Feign contract 测试和 Testcontainers 集成测试要按职责拆分。不要只写 happy path。

## 8. 每个批次的 Definition of Done

一个批次只有同时满足以下条件才算完成：

- 可编译的真实代码，无占位实现；
- 版本化 SQL migration 和必要数据回填；
- 单元/集成测试覆盖关键失败路径；
- 相关模块 `mvn ... test` 通过，或明确列出无法运行的外部依赖；
- API 向后兼容，破坏性变更提供过渡 endpoint/字段；
- 配置项有默认值、校验和生产说明，无硬编码 secret；
- 指标、日志、trace 和审计到位；
- 有回滚/降级方案；
- 更新 `docs/enterprise-upgrade/` 下的架构决策、状态机、时序图和运维说明。

## 9. 每次输出格式

每完成一个批次，按下面格式输出，不要只说“已完成”：

1. **确认的问题**：代码路径 + 原因；
2. **修改文件清单**：每个文件一句说明；
3. **数据库迁移**：migration 名称、字段/索引、回填和回滚；
4. **核心流程变化**：状态机或时序；
5. **测试清单及实际结果**；
6. **实际执行的构建命令及结果**；
7. **兼容性和风险**；
8. **下一批次入口条件**；
9. `git diff --stat` 摘要，并给出建议 commit message；不要自动 push。

## 10. 立即开始

现在从“批次 A”开始，先验证当前源码，再直接修改并运行测试。批次 A 完整通过后继续批次 B；任何批次失败时停止在该批次修复，不得跳过失败继续堆功能。优先保证安全、数据正确性、幂等和可恢复性，再做 Graph、Agent 和更多花哨能力。
