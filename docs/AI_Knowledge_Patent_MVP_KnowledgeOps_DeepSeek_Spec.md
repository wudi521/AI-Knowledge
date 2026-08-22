# AI-Knowledge 专利 MVP + Knowledge Ops 实施总任务书

> 基于现有后端 `AI-Knowledge`、前端 `AI-Knowledge-admin` 与最初《AI 客服知识库系统 · 高保真原型》，快速做出一个真实可运行、可演示、可排障、可继续扩展领域的企业级 AI 知识库 MVP。
>
> 第一验证领域：`PATENT`。

## 1. 仓库与范围

后端：`https://github.com/wudi521/AI-Knowledge`

前端：`https://github.com/wudi521/AI-Knowledge-admin`

前端仅修改：

```text
apps/web-antd
```

建议两个仓库使用同名分支：

```text
feat/patent-mvp-v0.1
```

开始前分别执行：

```bash
git status --short
git branch --show-current
git log -1 --oneline
```

不得覆盖未提交修改，不得自动 `git push`。

---

# 2. 产品结构：不要再按技术模块一比一暴露菜单

最终前端分三层：

```text
AI Knowledge Platform

业务层
├── 工作台
├── 知识空间
├── AI 问答
└── 质量中心

运营层 Knowledge Ops
├── 运营总览
├── 知识链路
├── 查询链路
├── 任务中心
├── 检索实验室
└── 评测中心

系统层
├── 模型管理
├── Prompt 管理
├── 权限
├── 审计
└── 运行配置
```

原则：

```text
后端按能力模块组织
前端按业务对象与用户任务组织
```

普通用户不需要理解 `Milvus / ES / Embedding / RRF / Chunk ID`；这些进入 Knowledge Ops。

---

# 3. 原型中的能力怎么处理

原型中的这些思想保留并接真实数据：

```text
闭环全景
入库管线
文档详情
Chunk 详情
检索实验室
链路追踪
证据中心
Claim 验证
版本管理
评测
模型网关
Prompt 管理
审计
成本
```

本次优先级：

```text
P0
- 文档详情
- Document Trace
- 任务中心
- Chunk Explorer
- Query Trace
- 检索实验室
- Evidence / Citation
- Evaluation

P1
- 模型运行详情
- Prompt 版本
- 成本

暂缓
- Agent 工作流设计器
- 完整 Domain Pack 管理
- Neo4j / GraphRAG
- 复杂 A/B 平台
```

---

# 4. 菜单调整

## 4.1 业务菜单

```text
AI 知识平台
├── 工作台
├── 知识空间
├── AI 问答
└── 质量中心
```

### 工作台

只展示业务有意义的数据：

```text
知识库数量
文档数量
待处理异常
最近发布
最近问答
质量状态

快捷入口：
+ 创建知识库
+ 上传资料
开始问答
```

不要默认展示 ES、Milvus、Token、Embedding 技术指标。

### 知识空间

知识库列表卡片：

```text
专利公开文献知识库
领域：专利 PATENT
资料：3
已发布：3
异常：0
质量：92
最后更新：10 分钟前

[进入知识库] [问答] [...]
```

进入知识库后必须成为 Workspace：

```text
专利公开文献知识库

[概览] [资料] [知识内容] [质量] [设置]

右上角：
[上传资料]
[开始问答]
```

用户不再为了完成业务跳转到“入库管线 / 版本管理 / Chunk 管理”等一级菜单。

## 4.2 Knowledge Ops 菜单

```text
Knowledge Ops
├── 运营总览
├── 知识链路
│   ├── 文档 Trace
│   ├── Chunk Explorer
│   └── Index Inspector
├── 查询链路
│   ├── Query Trace
│   ├── Evidence Trace
│   └── Claim Trace
├── 任务中心
├── 检索实验室
└── 评测中心
```

## 4.3 平台设置

```text
平台设置
├── 模型管理
├── Prompt 管理
├── 权限
├── 审计
└── 运行配置
```

若当前框架已有统一 RBAC / 审计，直接复用，不要再造一套。

---

# 5. 菜单权限

至少区分：

```text
AI_KNOWLEDGE_OPERATOR
AI_PLATFORM_ADMIN
```

`AI_KNOWLEDGE_OPERATOR`：

```text
工作台
知识空间
AI 问答
质量中心
```

`AI_PLATFORM_ADMIN` 额外拥有：

```text
Knowledge Ops
模型管理
Prompt 管理
运行配置
```

后端接口仍必须做权限校验，不能只靠前端隐藏。

---

# 6. 第一版业务 Happy Path

必须完整跑通：

```text
创建知识库
↓
选择 PATENT
↓
上传专利 PDF
↓
后台异步解析
↓
提取专利元数据
↓
识别专利结构
↓
知识切片
↓
Embedding
↓
ES + Milvus 索引
↓
质量检查
↓
异常驱动人工确认
↓
发布
↓
立即测试问答
↓
答案 + 引用 + 原文来源
```

用户不手动控制每个技术步骤。

---

# 7. 创建知识库

第一版表单：

```text
知识库名称
领域类型
描述
负责人（如现有框架已有）
```

领域：

```text
GENERAL
PATENT
```

默认 `GENERAL`。

选择 `PATENT` 后系统自动选择：

```text
PatentMetadataExtractor
PatentStructureParser
PatentSplitter
PatentQueryPolicy
PatentAnswerPolicy
```

业务创建页面不要暴露：

```text
chunkSize
overlap
embedding model
vector dimension
BM25 weight
vector weight
TopK
RRF 参数
```

---

# 8. Knowledge Workspace 页面

## 8.1 概览

展示：

```text
状态：已发布 / 处理中 / 有异常
领域：PATENT
文档：3
已发布：3
异常：0
最近发布
质量分

[上传资料]
[开始问答]
[查看异常]
```

## 8.2 资料

```text
文档名称
专利名称
申请号
公布号
当前阶段
发布状态
异常数
更新时间
操作
```

操作：

```text
查看
问答
查看处理链路（管理员）
重新解析（管理员）
重建索引（管理员）
```

## 8.3 知识内容

业务端统一叫“知识内容 / 知识片段”，不要默认叫 Chunk。

PATENT 类型筛选：

```text
全部
著录信息
摘要
权利要求
技术领域
背景技术
发明内容
实施方式
附图说明
```

## 8.4 质量

```text
解析完整度
元数据完整度
权利要求连续性
权利要求依赖完整性
索引完整度
评测结果
待处理异常
```

## 8.5 设置

```text
名称
描述
领域
权限
发布策略
```

已有正式数据后不允许随意修改领域；如需修改必须走迁移/重建索引流程。

---

# 9. 文档上传后的业务 UX

```text
一种粒子化磁涌装置及其使用方法.pdf

✓ 文件上传
✓ 文档解析
✓ 专利元数据
✓ 专利结构
✓ 知识构建
✓ 向量化
✓ 搜索索引
✓ 质量检查

状态：待发布

[查看解析结果]
[发布]
```

业务端隐藏 MinerU / ES / Milvus 等底层实现。

管理员点“查看处理链路”进入 Knowledge Ops。

---

# 10. PATENT 文档详情

推荐左右布局：

```text
┌──────────────────────┬────────────────────────────┐
│ 原文 / PDF            │ 专利结构化信息              │
│                      │                            │
│ Page 1               │ 发明名称                   │
│                      │ 申请号                     │
│                      │ 公布号                     │
│                      │ 申请日                     │
│                      │ 公布日                     │
│                      │ 申请人                     │
│                      │ 发明人                     │
│                      │ IPC                        │
│                      │ 权利要求数量               │
└──────────────────────┴────────────────────────────┘
```

如果 PDF Viewer 集成太重，v0.1 可先做到“打开原始文件 + 页码定位 + 解析文本预览”，不要为了 bbox 精确高亮拖慢 MVP。

---

# 11. 专利结构树

```text
▼ 著录信息
▼ 摘要
▼ 权利要求书
    ├── 权利要求 1 [独立]
    ├── 权利要求 2 [从属 → 1]
    ├── ...
    ├── 权利要求 8 [从属 → 1-7]
    └── 权利要求 9 [从属 → 8]
▼ 说明书
    ├── 技术领域
    ├── 背景技术
    ├── 发明内容
    ├── 附图说明
    └── 具体实施方式
▼ 附图
```

点击节点显示：

```text
原文
页码
类型
领域 metadata
```

---

# 12. PATENT 切片规则

新增 `PatentSplitter`：

```text
著录信息：单独
摘要：单独
权利要求：一项权利要求一个完整知识片段
技术领域：独立
背景技术：按结构段落
发明内容：按结构段落
附图说明：独立
具体实施方式：按章节 / 语义段落
```

权利要求禁止按固定 token 粗暴截断。

极长 Claim 可使用“完整 Claim Parent + Retrieval Child”，但最终 Evidence 必须能还原完整 Claim。

---

# 13. PATENT 元数据

文档级：

```json
{
  "domainCode": "PATENT",
  "applicationNo": "",
  "publicationNo": "",
  "filingDate": "",
  "publicationDate": "",
  "title": "",
  "applicants": [],
  "inventors": [],
  "ipcCodes": [],
  "claimCount": 0
}
```

知识片段级：

```json
{
  "domainCode": "PATENT",
  "sectionType": "CLAIMS",
  "sectionTitle": "权利要求书",
  "claimNo": 8,
  "claimType": "DEPENDENT",
  "dependsOn": [1,2,3,4,5,6,7],
  "pageStart": 2,
  "pageEnd": 2
}
```

测试专利数据不得硬编码到生产代码。

---

# 14. 异常驱动审核

不要要求人工审核所有 Chunk。

只有异常进入人工：

```text
申请号未识别
公布号未识别
申请号格式异常
权利要求编号不连续
权利要求依赖非法
文档页缺失
解析为空
结构识别异常
重复文档
索引不完整
```

正常文档：

```text
自动检查
→ 无异常
→ READY_TO_PUBLISH
```

---

# 15. 发布 UX

用户只点击“发布”。

后台：

```text
Validation
Index Consistency
Evaluation（若开启）
Publish
```

页面：

```text
正在发布

✓ 文档检查
✓ 知识构建
✓ 搜索索引
✓ 向量索引
✓ 质量检查

发布成功

[立即测试问答]
```

“立即测试问答”自动绑定当前知识库。

---

# 16. AI 问答

全局进入：

```text
知识范围：[选择知识库]
```

从 Workspace 进入：

```text
知识范围：专利公开文献知识库
```

自动绑定。

第一版建议单知识库：

```json
{"kbIds":[123]}
```

推荐问题可由 PATENT 领域模板生成：

```text
这件专利解决了什么技术问题？
这件专利的独立权利要求是什么？
权利要求 8 引用了哪些在先权利要求？
总结核心技术方案
比较几件专利的技术领域
```

---

# 17. Answer + Evidence 页面

```text
AI 回答正文 [C1] [C2]

依据 2 条

来源 1
文档名
申请号
公布号
章节
权利要求号
页码
版本
引用原文
[查看原文]
```

PATENT Citation DTO 至少：

```json
{
  "citationNo": 1,
  "chunkId": 10001,
  "documentId": 101,
  "documentName": "",
  "applicationNo": "",
  "publicationNo": "",
  "sectionType": "CLAIMS",
  "claimNo": 8,
  "pageStart": 2,
  "pageEnd": 2,
  "versionNo": "V1",
  "quote": ""
}
```

第一版“查看原文”至少能够打开文档详情并定位页码 / 结构节点。

---

# 18. Knowledge Ops：Document Trace

原型“入库管线 + 文档详情”升级为真实 Document Trace。

顶部：

```text
文档
知识库
领域
版本
Job ID
Trace ID
当前状态
总耗时
```

主链：

```text
UPLOAD
↓
PARSE
↓
PATENT_METADATA
↓
PATENT_STRUCTURE
↓
CHUNK
↓
EMBED
↓
ES_INDEX
↓
MILVUS_INDEX
↓
VALIDATE
↓
PUBLISH
```

每一个 Stage 必须可点击。

---

# 19. Stage Detail Drawer

点击阶段展示：

```text
状态
处理器
处理器版本
开始时间
结束时间
耗时
重试次数

Input Summary
Output Summary
Metrics
Error
```

例：

```text
PATENT_METADATA

Handler: PatentMetadataExtractor
Version: 1.0.0

Input:
ParsedDocument
pages=9
chars=18291

Output:
applicationNo=...
publicationNo=...
claimCount=9

Duration: 3128ms
```

支持“查看完整 JSON”。

以下必须脱敏：

```text
API Key
Authorization
Cookie
Token
密码
连接凭证
个人敏感字段
```

大对象使用 `payload_ref`，不要把完整 PDF / 大 Markdown / 超大模型输入全部塞数据库。

---

# 20. Chunk Explorer

管理员可查看：

```text
Chunk ID
文档
版本
类型
原文
父节点
页码
领域 Metadata
Embedding
ES 状态
Milvus 状态
被哪些 Query 命中
最终是否作为 Evidence
用户是否采纳
```

PATENT 示例：

```text
CHUNK_83021
CLAIM
claimNo=8
claimType=DEPENDENT
dependsOn=1-7
page=2

ES: OK
Milvus: OK
```

允许的管理员动作：

```text
查看来源
查看命中记录
重新向量化
重建索引
禁用
```

高风险操作必须权限 + 审计。

---

# 21. Query Trace

保留原型“链路追踪”的思想，接真实数据：

```text
REQUEST
↓
DOMAIN_CONTEXT
↓
QUERY_UNDERSTANDING
↓
QUERY_REWRITE
↓
SCOPE_FILTER
↓
BM25
↓
VECTOR
↓
RRF
↓
RERANK
↓
EVIDENCE
↓
PROMPT
↓
LLM
↓
CLAIM_VERIFY
↓
ANSWER
```

顶部：

```text
traceId
conversationId
messageId
tenant
kbIds
domain
总耗时
Token
成本
状态
```

---

# 22. Retrieval Stage 必须能展开原始候选

BM25：

```text
Rank / Chunk / Score / Document
```

Vector：

```text
Rank / Chunk / Similarity / Document
```

RRF：

```text
Chunk / BM25 Rank / Vector Rank / RRF Score
```

Rerank：

```text
Chunk / Before Rank / Rerank Score / After Rank
```

Evidence：

```text
Chunk / Support Score / Conflict / Selected / Reason
```

要能直接定位：

```text
召回错
融合错
Rerank 错
Evidence 错
LLM 错
```

---

# 23. LLM Stage

管理员可查看：

```text
Provider
Model
Model Version
Prompt Version
Temperature
Input Tokens
Output Tokens
Latency
Cost
System Prompt（脱敏）
Context
User Query
Raw Response
```

绝不展示 API Secret。

---

# 24. 业务页到 Ops 的深链

文档页管理员菜单：

```text
查看处理链路
查看索引状态
查看质量报告
重新解析
重建索引
```

问答页管理员菜单：

```text
查看答案链路
查看检索结果
加入评测集
标记答案错误
```

必须直接定位对应 document/job/trace，不要让管理员重新到 Ops 列表里搜索。

---

# 25. 任务中心

```text
Job ID
对象
知识库
领域
当前 Stage
进度
状态
开始时间
耗时
重试次数
操作
```

筛选：

```text
RUNNING
FAILED
SUCCESS
PATENT
知识库
时间
```

失败示例：

```text
stage=Milvus Index
error=dimension mismatch
retry=3/5

[重试当前阶段]
[查看链路]
```

阶段重试必须幂等，不能简单重跑整条链冒充 Retry。

---

# 26. 检索实验室

保留原型能力：

```text
混合检索
仅 BM25
仅 Vector
```

可开关：

```text
Query Rewrite
Query Decomposition
Reranker
```

输入：

```text
query
kb
domain
```

输出：

```text
Understanding
Rewrite
BM25
Vector
RRF
Rerank
Evidence
```

v0.1 不做复杂 A/B 实验平台。

---

# 27. 质量与 Evaluation

PATENT MVP 至少准备 Golden Cases。

指标：

```text
Metadata Accuracy
Claim Number Accuracy
Claim Dependency Accuracy
Recall@5
MRR
Citation Accuracy
Faithfulness
Abstention Accuracy
```

业务端只展示结论；Knowledge Ops 展示失败用例与根因。

---

# 28. 领域实现：先轻量，不做完整 Domain Pack

本次最少新增：

```java
DomainIngestionAdapter
DomainQueryPolicy
DomainAnswerPolicy
```

实现：

```text
General...
Patent...
```

使用 Registry / Map 选择策略，禁止把 `if (PATENT) ... else if (LEGAL)` 散落核心代码。

建议：

```java
public interface DomainIngestionAdapter {
    String domainCode();
    DomainDocumentResult process(ParsedDocument document, DomainContext context);
}
```

```java
public interface DomainQueryPolicy {
    String domainCode();
    DomainQuery analyze(String query, DomainContext context);
    RetrievalPolicy retrievalPolicy(DomainQuery query, DomainContext context);
}
```

```java
public interface DomainAnswerPolicy {
    String domainCode();
    AnswerPolicy resolve(DomainQuery query, DomainContext context);
}
```

---

# 29. DomainContext

```java
public record DomainContext(
    Long tenantId,
    Long knowledgeBaseId,
    Long documentId,
    Long versionId,
    String domainCode,
    String domainVersion,
    Long userId,
    Map<String, Object> attributes
) {}
```

贯穿：

```text
Ingestion
Retrieval
Evidence
Answer
Evaluation
Trace
```

---

# 30. PATENT 最小实现类

```text
PatentMetadataExtractor
PatentClaimParser
PatentStructureParser
PatentSplitter
PatentQueryPolicy
PatentAnswerPolicy
```

禁止重新实现已有：

```text
Embedding Service
Elasticsearch Service
Milvus Service
Evidence Engine
Model Gateway
Chat Pipeline
Evaluation Engine
```

必须复用现有能力。

---

# 31. Trace 数据模型

创建新表前先检查仓库当前已有表/实体；有同义能力则扩展，禁止重复造系统。

如缺失，最小建议：

## ai_ingestion_job

```text
id
tenant_id
trace_id
kb_id
document_id
version_id
domain_code
status
current_stage
progress
retry_count
error_code
error_message
started_at
finished_at
create_time
update_time
```

## ai_ingestion_task

```text
id
tenant_id
job_id
stage_code
stage_order
handler
handler_version
attempt
status
input_summary_json
output_summary_json
metrics_json
payload_ref
error_code
error_message
started_at
finished_at
create_time
update_time
```

## ai_query_trace

```text
id
tenant_id
trace_id
conversation_id
message_id
user_id
kb_ids_json
domain_code
raw_query
status
final_answer
latency_ms
input_tokens
output_tokens
cost
started_at
finished_at
create_time
```

## ai_query_stage

```text
id
tenant_id
trace_id
stage_code
stage_order
handler
handler_version
status
input_summary_json
output_summary_json
metrics_json
payload_ref
error_code
error_message
started_at
finished_at
```

字段名须适配现有 BaseDO / 多租户规范，不要机械覆盖现有结构。

---

# 32. Trace 记录代码

不要在每个 Service 到处手写 insert/update。

统一：

```text
PipelineTraceRecorder
```

建议接口：

```java
<T> T recordStage(
    TraceContext context,
    StageCode stage,
    Supplier<T> action
)
```

行为：

```text
START
→ 保存输入摘要
→ 执行业务
→ SUCCESS + 输出摘要 + metrics

异常：
FAILED + error
→ 继续抛出原异常
```

不得吞异常。

---

# 33. TraceContext

```java
public record TraceContext(
    String traceId,
    Long jobId,
    Long documentId,
    Long versionId,
    Long conversationId,
    Long messageId,
    Long tenantId,
    String domainCode
) {}
```

如现有框架已有 TraceId/MDC，做关联，不要生成冲突的第二套 Trace。

---

# 34. 事务和异步原则

数据库事务中禁止直接执行：

```text
ES
Milvus
LLM
HTTP 下载
MinerU
外部 API
```

跨系统流程：

```text
状态持久化
→ 事务提交
→ 异步执行
→ 更新 Stage / Job 状态
```

优先复用现有 Kafka / 异步机制。

---

# 35. Index 一致性

发布前至少校验：

```text
MySQL chunks == expected
ES indexed == expected
Milvus indexed == expected
```

Knowledge Ops 显示：

```text
MySQL 32
ES    32
Milvus 32
Consistency: PASS
```

不一致：

```text
Consistency: FAILED
[修复索引]
```

---

# 36. 前端目录建议

优先在现有 `apps/web-antd/src/views/ai` 中增量改造：

```text
views/ai/
├── knowledge/
│   ├── index.vue
│   ├── workspace/
│   │   ├── index.vue
│   │   ├── overview.vue
│   │   ├── documents.vue
│   │   ├── contents.vue
│   │   ├── quality.vue
│   │   └── settings.vue
│   └── components/
├── document/
│   ├── detail.vue
│   └── components/
│       ├── PatentMetadataPanel.vue
│       ├── PatentStructureTree.vue
│       └── DocumentQualityPanel.vue
├── chat/
│   └── components/
│       ├── KnowledgeScopeSelector.vue
│       ├── EvidenceCard.vue
│       └── CitationSourceCard.vue
├── ops/
│   ├── overview/
│   ├── document-trace/
│   ├── chunk-explorer/
│   ├── query-trace/
│   ├── jobs/
│   ├── retrieval-lab/
│   └── evaluation/
└── model/
```

如果已有页面可复用，原地改造，不为符合本目录而复制页面。

---

# 37. 通用 Vue 组件

建议抽取：

```text
PipelineTimeline.vue
StageDetailDrawer.vue
StatusBadge.vue
JsonViewer.vue
TraceHeader.vue
ErrorPanel.vue
PatentMetadataPanel.vue
PatentStructureTree.vue
KnowledgeFragmentList.vue
EvidenceCard.vue
CitationSourceCard.vue
RetrievalCandidateTable.vue
```

不要堆成单个超大 Vue 文件。

---

# 38. UI 风格

旧原型只作为：

```text
信息架构 + 交互参考
```

不要把原型黑色工业风 CSS 整体复制到 Vben。

使用当前：

```text
Vben + Ant Design Vue
Page / Card / Table / Drawer / Tag / Timeline
现有主题系统
```

继承的是：

```text
Pipeline
Timeline
Status
Evidence
Trace
Input/Output 展开
```

---

# 39. 页面线框

## Knowledge Workspace

```text
┌──────────────────────────────────────────────┐
│ 专利公开文献知识库       [上传资料] [开始问答]│
│ PATENT · 已发布 · 3文档 · 0异常              │
├──────────────────────────────────────────────┤
│ 概览 | 资料 | 知识内容 | 质量 | 设置          │
├──────────────────────────────────────────────┤
│ 当前 Tab 内容                                │
└──────────────────────────────────────────────┘
```

## Document Trace

```text
┌──────────────────────────────────────────────┐
│ 文档 / Job / Trace / 状态 / 总耗时           │
├──────────────────────────────────────────────┤
│ Upload → Parse → Metadata → Structure → ...  │
├───────────────────────┬──────────────────────┤
│ 阶段结果 / 时间线      │ 异常 / 指标 / 元信息   │
└───────────────────────┴──────────────────────┘
```

## Query Trace

```text
┌──────────────────────────────────────────────┐
│ Query / traceId / KB / domain / latency      │
├──────────────────────────────────────────────┤
│ Understand → Rewrite → BM25/Vector → ...     │
├───────────────────────┬──────────────────────┤
│ 当前 Stage 详细结果    │ tokens/cost/model      │
└───────────────────────┴──────────────────────┘
```

---

# 40. API 原则

沿用当前 Controller / VO / DTO / permission 规范。

需要提供的能力：

```text
知识库：create/update/get/page
文档：upload/detail/list/publish
Document Trace：trace/stage detail/retry
Chunk：page/detail/index status/hit history
Chat：send + kbIds
Query Trace：page/detail/stages
Retrieval Lab：execute
Evaluation：run/result
```

前端不得绕过 API 直接依赖数据库字段。

---

# 41. 前后端字段契约

重点一致：

```text
domainCode
domainMetadata
kbIds
sectionType
claimNo
claimType
dependsOn
pageStart
pageEnd
traceId
jobId
stageCode
```

TypeScript 禁止通过大面积 `any` 绕过契约。

---

# 42. 状态机

Document：

```text
UPLOADED
PROCESSING
REVIEW_REQUIRED
READY_TO_PUBLISH
PUBLISHING
PUBLISHED
FAILED
ARCHIVED
```

Stage：

```text
QUEUED
RUNNING
SUCCESS
FAILED
SKIPPED
CANCELLED
```

前后端语义一致。

---

# 43. PATENT 测试要求

三份真实专利 PDF 必须真实解析，禁止硬编码。

至少测试：

```text
权利要求 8 引用了哪些权利要求？
独立权利要求是什么？
几件专利分别解决什么技术问题？
哪件专利涉及分区域视频/图片处理？
哪件专利涉及电脑绣替代印花？
```

拒答测试：

```text
这件专利是否已经授权？
```

如果知识库只有公开申请文本且没有授权状态证据，应回答“依据当前知识库资料无法确认授权状态”，不得推断。

---

# 44. Knowledge Ops 验收

任意文档必须能：

```text
打开 Document Trace
查看每个 Stage
查看 input/output 摘要
查看 handler/version
查看耗时
查看索引数
查看错误
失败阶段可重试
```

任意一次问答必须能：

```text
打开 Query Trace
看到 Understanding
看到 BM25 原始结果
看到 Vector 原始结果
看到 RRF
看到 Rerank
看到 Evidence
看到 Prompt/Model
看到最终 Answer
```

---

# 45. 数据安全与审计

统一实现 `TraceSanitizer`，落库前脱敏：

```text
apiKey
Authorization
password
secret
token
cookie
database password
private customer data
```

以下操作必须审计：

```text
重新解析
重试 Stage
重建索引
禁用 Chunk
重新向量化
发布
回滚
修改 Prompt
修改模型配置
```

---

# 46. v0.1 明确不做

```text
完整 Domain Pack 平台
动态 JAR 插件上传
Neo4j
GraphRAG
复杂 Agent Runtime
工作流设计器
全量 OpenTelemetry 拓扑
Grafana 替代品
复杂 AB Test 平台
完整 PDF bbox 高亮
跨领域自动分类
专利授权概率预测
专利侵权分析
专利自动撰写
```

---

# 47. 实施批次

## Batch A：菜单 + Workspace + 最小领域字段

后端：

```text
domainCode
domainMetadata
DTO
migration
GENERAL 兼容
PATENT 枚举
```

前端：

```text
菜单重组
Knowledge Workspace
创建知识库领域选择
业务 / Ops 权限
```

验收：现有 GENERAL 不坏，可创建 PATENT KB。

## Batch B：PATENT 入库 + Document Trace

```text
PatentMetadataExtractor
PatentClaimParser
PatentStructureParser
PatentSplitter
Ingestion Job / Stage Trace
Document Trace 页面
Stage Detail Drawer
Task Center
```

验收：3 PDF 真实处理，元数据/Claim 正确，Trace 可见。

## Batch C：发布 + 问答 + Query Trace

```text
发布
Chat kbIds
Patent Query Policy
Patent Answer Policy
Evidence source metadata
Query Trace
Retrieval candidates
Citation cards
```

验收：问题 → 正确召回 → 回答 → 引用 → Trace。

## Batch D：质量 + 检索实验室 + 收口

```text
Golden Cases
Evaluation
Retrieval Lab
异常中心
业务 / Ops 深链
前后端完整构建
演示脚本
```

---

# 48. 每批开发纪律

每完成一批必须报告：

```text
1. 实际修改文件
2. migration
3. API 变化
4. 状态机变化
5. 测试
6. 实际构建命令和结果
7. git diff --stat
8. 未解决问题
9. 回滚方案
10. 建议 commit message
```

当前批次失败，不得跳过继续堆功能。

---

# 49. 测试

后端最低：

```text
PatentMetadataExtractorTest
PatentClaimParserTest
PatentSplitterTest
ClaimDependencyTest
DomainStrategyRegistryTest
IngestionTraceRecorderTest
PipelineRetryTest
QueryTraceTest
TenantIsolationTest
```

前端至少执行真实脚本（先读取 package.json）：

```bash
pnpm install --frozen-lockfile
pnpm check:type
pnpm lint
pnpm build:antd
```

后端读取真实 Maven 模块结构后，至少执行 AI 模块 compile/test 和最终 server compile/package。

---

# 50. 代码纪律

```text
不推倒重写
不复制现有能力
不把 domain if/else 散落全项目
不硬编码测试专利
不吞异常
不伪造 Trace
不在前端 mock 真实运行状态
不在事务里调用不可回滚远程服务
不靠 UI 隐藏替代后端权限
不把 Secret 写进 Trace
不自动 git push
```

---

# 51. 原型 → 新版映射

```text
旧：闭环全景
新：Knowledge Ops / 运营总览

旧：知识库列表
新：知识空间

旧：入库管线
新：Knowledge Ops / 任务中心 + Document Trace

旧：知识审核
新：Workspace / 质量 / 异常审核

旧：版本管理
新：Workspace / 资料 / 版本历史

旧：检索实验室
新：Knowledge Ops / 检索实验室

旧：链路追踪
新：Knowledge Ops / Query Trace

旧：证据中心
新：Knowledge Ops / Evidence Trace

旧：Claim 验证
新：Query Trace Claim Stage + 高级查询页

旧：模型网关
新：平台设置 / 模型管理

旧：Prompt 管理
新：平台设置 / Prompt 管理

旧：评测任务 + 评测指标
新：质量中心（业务） + Knowledge Ops Evaluation（技术）

旧：权限 / 审计 / 成本
新：平台设置 / 治理

旧：文档详情
新：Workspace / 资料 / 文档详情

旧：Chunk 详情
新：业务“知识片段详情” + Ops “Chunk Explorer”

旧：会话详情
新：AI 问答会话详情 + Query Trace 深链
```

---

# 52. DeepSeek 开工前必须先输出

先审查当前最新代码并输出：

```text
A. 后端已有能力映射
B. 前端已有页面映射
C. 原型页面 → 新菜单映射
D. 可直接复用项
E. 需要新增项
F. 需要收敛/废弃项
G. 数据库变更列表
H. Batch A 精确修改文件计划
```

随后立即执行 Batch A，不要停在方案讨论。

如果本任务书与仓库最新代码不一致：

```text
以实际代码为准
但必须明确报告差异
禁止静默猜测
```

---

# 53. 最终成功标准

不是“菜单画出来”，而是可以现场演示：

```text
创建 PATENT 知识库
↓
上传真实专利 PDF
↓
文档状态实时变化
↓
打开 Document Trace
↓
看到真实每步 Input / Output / Handler / Duration
↓
发布
↓
开始问答
↓
答案带精确专利来源
↓
打开 Query Trace
↓
看到真实 BM25 / Vector / RRF / Rerank / Evidence / LLM
↓
错误时可以定位原因
```

PATENT 只是第一个领域。未来 `LEGAL / CONTRACT / TELECOM / MANUFACTURING` 接入时，业务主流程与 Knowledge Ops 主流程不应重做；领域差异逐步沉淀到 Metadata / Structure / Chunk Policy / Query Policy / Answer Policy / Evaluation。
