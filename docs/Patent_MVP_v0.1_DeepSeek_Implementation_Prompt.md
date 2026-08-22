# AI-Knowledge 专利领域 MVP v0.1：DeepSeek 实施任务书

## 0. 任务目标

在现有 `AI-Knowledge` 项目上，不推倒重写，不新建一套独立平台，用最小改造打通一条可以真实演示的专利知识库纵向链路：

```text
创建 PATENT 知识库
  -> 上传专利 PDF
  -> 解析著录信息与章节
  -> 按摘要/权利要求/说明书切片
  -> 人工确认并发布
  -> BM25 + 向量混合检索
  -> 基于证据回答
  -> 展示申请号、公布号、章节、权利要求号、页码等来源
```

本版本的目标不是一次性完成通用 Domain Pack、知识图谱、Agent、专利法律分析，而是验证：

1. 当前平台能否快速承载一个真实专业领域；
2. 专利领域是否可以通过少量通用扩展点接入，而不是把行业逻辑散落在核心代码中；
3. 三份不同技术方向的专利文档能否稳定解析、检索、回答并追溯来源；
4. 后续增加法律、制造、电信等领域时，是否能够复用本次形成的领域接入骨架。

---

## 1. 当前源码基线与实施约束

### 1.1 已有能力，必须复用

当前项目已经具备以下基础，禁止重复造轮子：

- `yudao-module-ingestion`
  - PDF / Word / Excel / PPT / 图片解析；
  - MinerU 优先、PDFBox 降级；
  - 结构化元素、页码、标题链；
  - 多种 Chunk 策略；
  - Embedding；
  - MySQL Chunk 持久化。
- `yudao-module-knowledge`
  - 知识库、文档、版本、审核、冲突、发布；
  - 发布后调用 ingestion 写入 ES / Milvus；
  - 知识库权限。
- `yudao-module-retrieval`
  - Query Analysis；
  - BM25 + Vector；
  - RRF；
  - Reranker；
  - 已发布过滤。
- `yudao-module-evidence`
  - Evidence；
  - 回答生成；
  - Claim Verification；
  - Sufficiency Judge；
  - 引用编号。
- `yudao-module-eval`
  - 评测 Case、任务和指标。
- `yudao-module-chat`
  - 会话、历史上下文、证据评估调用、引用 Chunk。
- `yudao-module-model`
  - Model Gateway、重试、熔断、调用统计。
- 现有 Vben 前端已经有知识库、文档、Chunk、检索、审核、版本、评测、工作台页面。

### 1.2 已确认的现状问题，本次必须绕开或修复

1. `ParentChildSplitter` 把本地 `parentSeq` 当数据库 `parentId`，当前不能用于专利 MVP。
2. 检索侧没有真正实现 Child Hit -> Parent Expansion。
3. 审核抽取 Prompt 和产品识别 Prompt 偏企业客服，不适合专利。
4. Query Analysis 固定 Prompt 偏保修、退款、产品品牌。
5. SearchService 存在产品/品牌一致性门禁，专利知识库必须跳过。
6. AnswerGenerator 的角色写死为企业客服知识库，需要领域化。
7. ChatPipeline 当前传 `kbIds=null`，会搜索全部可见知识库；专利 MVP 必须允许用户选择知识库。
8. Evidence 引用目前只有文档名、版本和 Chunk，缺少专利公布号、申请号、章节、权利要求号和页码。
9. 发布流程启用了 Eval Gate；本地首次启动的专利知识库没有历史评测结果，会无法完成首次发布。
10. 当前完整 Domain Framework 尚不存在，本次只建立轻量扩展点，不做热加载 JAR、插件市场或独立领域微服务。

---

## 2. MVP 功能边界

### 2.1 本次必须完成

#### A. 专利文档入库

- 创建领域为 `PATENT` 的知识库；
- 上传 PDF；
- 优先使用 MinerU，失败时使用 PDFBox；
- 提取著录信息；
- 识别专利章节；
- 一条权利要求对应一个完整 Chunk；
- 保存权利要求依赖关系；
- 保存章节、页码、申请号、公布号等元数据；
- 进入文档级人工审核；
- 发布后写 ES 和 Milvus。

#### B. 专利检索和问答

支持：

- 按申请号、公布号、名称、申请人、发明人查询；
- 查询摘要、技术领域、背景技术、发明内容、具体实施方式；
- 精确查询某一权利要求；
- 查询独立权利要求和从属权利要求；
- 查询某项权利要求引用了哪些在先权利要求；
- 基于当前导入文档做多文档对比；
- 回答必须带来源；
- 证据不足时拒绝猜测。

#### C. 前端最小闭环

- 知识库表单增加领域选择；
- 文档详情显示专利元数据；
- 工作台或聊天页增加知识库选择；
- 回答显示来源卡片；
- 来源卡片显示：文档名、公布号、申请号、章节、权利要求号、页码、版本。

#### D. 最小自动化评测

- 导入本任务书附带的 `patent_mvp_golden_cases.jsonl`；
- 至少实现著录信息、权利要求、跨文档比较、拒答和安全边界测试；
- 生成实际运行报告。

### 2.2 本次明确不做

- 不做 Neo4j；
- 不做 GraphRAG；
- 不做完整 Entity / Relation 知识图谱；
- 不做 Agent 自动申请专利；
- 不做自动撰写完整专利申请文件；
- 不做全球专利库联网检索；
- 不做现有技术检索和查新；
- 不判断新颖性、创造性、实用性；
- 不预测授权概率；
- 不判断侵权、无效或保护范围结论；
- 不对文档中的医学、物理或商业效果作真实性背书；
- 不解决千万级数据、集群容灾和全部企业级 P0 问题。

---

## 3. 领域接入的最小通用设计

本次不能只写 `if (patent)` 到处散落，但也不能过度设计完整插件平台。

采用：

```text
通用 Core
  + 轻量 Domain Policy/Adapter
  + PATENT 实现
  + GENERAL 默认实现
```

### 3.1 知识库领域标识

给 `ai_knowledge_base` 增加：

```sql
domain_code varchar(32) NOT NULL DEFAULT 'GENERAL'
```

当前枚举：

```text
GENERAL
PATENT
```

要求：

- 后端 Save/Resp/Page DTO 全部透传；
- 前端创建和编辑知识库时可选择；
- 默认保持 `GENERAL`，不能破坏历史数据；
- 文档查询 RPC 必须返回其知识库的 `domainCode`。

### 3.2 文档领域元数据

给 `ai_document` 增加：

```sql
domain_metadata json NULL
```

如果当前数据库兼容策略不允许直接使用 JSON，可使用 `longtext`，Java 侧统一以 JSON 对象读写。

专利元数据至少包含：

```json
{
  "domainCode": "PATENT",
  "applicationNo": "202311344028.2",
  "publicationNo": "CN 122621758 A",
  "filingDate": "2023-10-17",
  "publicationDate": "2026-08-21",
  "title": "一种分区域视频和图片的储存和下载技术",
  "applicants": ["韩信"],
  "inventors": ["韩信"],
  "agency": null,
  "agents": [],
  "ipcCodes": ["H04N 21/238", "H04N 21/438"],
  "abstract": "...",
  "claimCount": 7,
  "sourceType": "CN_PATENT_APPLICATION_PUBLICATION",
  "extractorVersion": "patent-mvp-1.0"
}
```

### 3.3 轻量领域扩展接口

在适合的公共包中定义领域枚举与策略，不要增加新微服务。

建议接口：

```java
public interface DomainIngestionAdapter {
    String domainCode();

    DomainDocumentMetadata extractMetadata(ParsedDocument document,
                                           KnowledgeDocumentRespDTO source);

    List<Chunk> split(ParsedDocument document,
                      SplitParams params,
                      DomainDocumentMetadata metadata);
}
```

```java
public interface DomainQueryPolicy {
    String domainCode();

    String queryAnalysisPrompt();

    boolean enableProductGate();

    boolean enableSlotDetection();
}
```

```java
public interface DomainAnswerPolicy {
    String domainCode();

    String answerPrompt();
}
```

必须提供：

```text
GeneralDomainIngestionAdapter / GeneralDomainQueryPolicy / GeneralDomainAnswerPolicy
PatentDomainIngestionAdapter / PatentDomainQueryPolicy / PatentDomainAnswerPolicy
```

实现要求：

- 使用 Spring Bean 列表注册和 Map 索引；
- 未找到领域实现时回退 `GENERAL`；
- 领域策略不能绕过租户、ACL、已发布状态和证据校验；
- 不允许领域实现直接访问其他租户数据；
- 不实现运行时上传 JAR。

---

## 4. 数据库迁移

在项目现有 `sql` 迁移目录中新增正式迁移文件，不修改历史 SQL。

建议文件：

```text
sql/migrate-20260822-patent-mvp-v01.sql
```

迁移至少包括：

```sql
ALTER TABLE ai_knowledge_base
    ADD COLUMN domain_code varchar(32) NOT NULL DEFAULT 'GENERAL' COMMENT '知识领域: GENERAL/PATENT';

ALTER TABLE ai_document
    ADD COLUMN domain_metadata json NULL COMMENT '领域文档元数据';
```

如果数据库版本不支持 JSON：

```sql
ALTER TABLE ai_document
    ADD COLUMN domain_metadata longtext NULL COMMENT '领域文档元数据(JSON)';
```

迁移要求：

- 可在已有数据环境执行；
- 不丢数据；
- 有字段存在性防护，或遵循项目当前迁移规范；
- 提供回滚 SQL，但不要自动执行回滚；
- 补齐 DO、Mapper、VO、Convert、API DTO 和 OpenAPI 注释。

---

## 5. 专利元数据提取

### 5.1 新增组件

建议：

```text
yudao-module-ingestion-server
└── .../domain/patent/
    ├── PatentDomainIngestionAdapter.java
    ├── PatentMetadataExtractor.java
    ├── PatentSplitter.java
    ├── PatentSectionDetector.java
    ├── PatentClaimParser.java
    ├── PatentMetadata.java
    └── PatentChunkMetadata.java
```

### 5.2 提取原则

使用：

```text
规则优先
  -> 结构标题和正则
  -> 缺失字段才调用 LLM
  -> LLM 结果必须经格式校验
```

不要让 LLM 重新生成整份专利文本。

### 5.3 必须识别的著录字段

- 申请号；
- 申请日；
- 申请人；
- 发明人；
- 专利代理机构；
- 专利代理师；
- IPC 分类号；
- 发明名称；
- 摘要；
- 申请公布号；
- 申请公布日；
- 权利要求数量。

### 5.4 正则和容错要求

需要兼容：

- 空格：`202311042981 .1`；
- 公布号空格：`CN 122604134 A`；
- 中文全角标点；
- PDF 换行导致的字段断裂；
- 多个申请人、发明人、IPC；
- 字段缺失；
- OCR 误差。

任何无法确定的字段必须为 `null` 或空数组，禁止猜测。

### 5.5 元数据持久化

新增或扩展 Knowledge RPC：

```java
updateDocumentDomainMetadata(Long documentId, String domainMetadata)
```

要求：

- 只能更新当前租户文档；
- JSON 大小设置合理上限；
- 不把全文重复写进元数据；
- 更新失败时入库任务失败，不允许元数据和 Chunk 静默不一致。

---

## 6. PatentSplitter 设计

### 6.1 章节类型

必须输出以下 sectionType：

```text
BIBLIOGRAPHIC
ABSTRACT
CLAIMS
TECHNICAL_FIELD
BACKGROUND
INVENTION_SUMMARY
DRAWING_DESCRIPTION
EMBODIMENT
DRAWING
OTHER
```

### 6.2 Chunk 类型

```text
PATENT_BIBLIO
PATENT_ABSTRACT
PATENT_CLAIM
PATENT_DESCRIPTION
PATENT_DRAWING
```

### 6.3 权利要求切片规则

核心约束：

> 一条权利要求必须保持完整，不能因为普通 token 上限被切成多个互不完整的 Chunk。

识别规则：

- 权利要求从 `1.`、`1．`、`1、` 等编号开始；
- 下一权利要求编号出现时结束；
- 跨行文字要合并；
- `根据权利要求1所述` 识别为从属权利要求；
- `根据权利要求1至7中任意一项所述` 解析 `dependsOn=[1,2,3,4,5,6,7]`；
- 没有引用其他权利要求时标记 `INDEPENDENT`；
- 超长权利要求可以保留完整文本，即便超过普通 `maxTokens`；
- 权利要求必须保存 `claimNo`、`claimType`、`dependsOn`、`pageStart`、`pageEnd`。

### 6.4 说明书切片规则

- 按章节优先；
- 章节内部再按段落或语义窗口切分；
- 每个 Chunk 都保留章节标题；
- 每个 Chunk 保留页码范围；
- 不使用当前有缺陷的 `ParentChildSplitter`；
- 说明书 Chunk 目标 400~700 tokens；
- overlap 只允许在同一章节内发生；
- 不允许把权利要求和说明书混在同一个 Chunk。

### 6.5 搜索内容头

每个专利 Chunk 的 `content` 前增加稳定的结构化搜索头，以增强 BM25 和向量召回：

```text
[专利名称] 一种粒子化磁涌装置及其使用方法
[申请号] 202311832214.0
[公布号] CN 122619519 A
[章节] 权利要求书
[权利要求] 1
[页码] 2

<原始正文>
```

要求：

- 原始正文不能丢失或改写；
- 搜索头与正文之间有明确分隔；
- 前端展示时可以单独展示元数据，也可以折叠搜索头。

### 6.6 Chunk metadata

复用当前 `ai_chunk.metadata`，保存 JSON：

```json
{
  "domainCode": "PATENT",
  "applicationNo": "202311832214.0",
  "publicationNo": "CN 122619519 A",
  "title": "一种粒子化磁涌装置及其使用方法",
  "sectionType": "CLAIMS",
  "sectionTitle": "权利要求书",
  "claimNo": 8,
  "claimType": "DEPENDENT",
  "dependsOn": [1,2,3,4,5,6,7],
  "pageStart": 2,
  "pageEnd": 2,
  "extractorVersion": "patent-mvp-1.0"
}
```

---

## 7. 入库和审核流程改造

### 7.1 IngestServiceImpl

在不破坏 GENERAL 流程的前提下：

```text
loadDocument
  -> 获取 domainCode
  -> parseDocument
  -> ContextEnricher
  -> DomainIngestionAdapter.extractMetadata
  -> 更新 ai_document.domain_metadata
  -> DomainIngestionAdapter.split
  -> Embedding
  -> MySQL Chunk
  -> notifyParsed
```

PATENT 知识库时必须走 `PatentDomainIngestionAdapter`。

### 7.2 专利审核模式

当前 `ReviewItemServiceImpl` 使用客服知识条目抽取和产品识别，不适合专利。

PATENT 流程：

```text
文档解析成功
  -> 校验 Chunk 非空
  -> 跳过客服 ReviewItem 抽取
  -> 跳过产品/品牌提取
  -> 版本状态进入 REVIEW
  -> 文档状态进入 REVIEW
  -> 管理员确认后点击发布
```

要求：

- 不能自动发布空文档；
- 不创建虚假的 PRICE/POLICY/FAQ 条目；
- GENERAL 流程保持原样；
- 发布仍然走既有 Version 发布、索引和状态机。

### 7.3 本地首次发布的 Eval Gate

禁止直接关闭所有环境的评测闸门。

新增本地演示 Profile，例如：

```text
application-patent-mvp.yaml
```

只在该 Profile 中：

```yaml
yudao:
  eval:
    gate:
      enabled: false
```

要求：

- `dev/prod` 原默认值不改变；
- README 明确说明：首次建立样本索引时临时关闭；
- 样本发布后导入 Golden Cases、运行评测；
- 评测跑通后再开启 Gate；
- 不允许在 Java 代码中永久硬编码跳过评测。

---

## 8. 查询分析与检索改造

### 8.1 领域识别

检索必须根据所选 `kbIds` 获取领域：

- 单个知识库：直接使用其 `domainCode`；
- 多个知识库同领域：使用该领域；
- 多个知识库跨领域：MVP 暂时拒绝，并提示一次只选择同一领域知识库；
- 无法获取领域：回退 GENERAL。

### 8.2 PATENT Query Analysis Prompt

新增专利查询 Prompt，JSON 输出保持与现有 QueryAnalysis 兼容：

```json
{
  "intent": "CLAIM_LOOKUP",
  "entities": ["202311832214.0", "权利要求8"],
  "products": [],
  "rewrites": ["申请号202311832214.0的权利要求8", "粒子化磁涌装置 权利要求8"],
  "sub_questions": []
}
```

意图枚举：

```text
BIBLIOGRAPHIC_LOOKUP
ABSTRACT_LOOKUP
CLAIM_LOOKUP
CLAIM_DEPENDENCY
TECHNICAL_SOLUTION
BACKGROUND_LOOKUP
EMBODIMENT_LOOKUP
DOCUMENT_COMPARISON
OUT_OF_SCOPE
OTHER
```

提示词必须识别：

- 申请号；
- 公布号；
- 专利名称；
- 申请人；
- 发明人；
- 权利要求号；
- 章节；
- 比较对象。

### 8.3 产品门禁

PATENT 领域：

```text
enableProductGate = false
```

GENERAL 领域保持当前逻辑。

不能通过简单删除产品门禁破坏 GENERAL 客服场景。

### 8.4 Slot Detection

专利 MVP 初期关闭通用槽位检测：

```text
enableSlotDetection = false
```

避免使用客服槽位自动反问。

后续可为专利增加特定槽位，例如 `applicationNo`、`publicationNo`、`claimNo`，但不作为本次阻塞项。

### 8.5 精确编号增强

当查询包含以下模式时，必须把原始编号保留在 rewrites 中：

```text
202311344028.2
CN 122621758 A
权利要求1
权利要求8
```

不得让 LLM 改写时丢掉小数点、空格或字母后缀。

---

## 9. Chat 知识库绑定

### 9.1 请求参数

给 `ChatSendReqVO` 增加：

```java
private List<Long> kbIds;
```

或在 MVP 中使用单个 `kbId`，但内部统一转为 List。

### 9.2 ChatPipeline

当前 Evidence 调用中的 `kbIds=null` 必须改为用户选择的知识库：

```text
ChatSendReqVO.kbIds
  -> ChatPipeline
  -> EvidenceRpcAdapter.evaluate
  -> EvidenceEvaluateReqDTO.kbIds
  -> Retrieval
```

要求：

- 后端再次做可见知识库交集，不信任前端；
- 用户未选择时可以使用当前会话绑定的知识库；
- 新会话首次提问仍未选择时返回明确提示，不默认搜索全部知识库；
- 会话需要保存绑定的 `kbIds` 或至少保存 `kbId`，后续轮次复用；
- PATENT MVP 页面先限制选择一个知识库。

---

## 10. 证据和回答领域化

### 10.1 Evidence 元数据

扩展检索结果和 Evidence DTO：

```text
chunkMetadata
applicationNo
publicationNo
sectionType
sectionTitle
claimNo
pageStart
pageEnd
```

不要求每个字段都新增数据库列，可以从 Chunk metadata 解析。

### 10.2 Patent Answer Prompt

专利回答 Prompt 必须包含：

```text
你是专利公开文献知识库助手。
只依据当前提供的专利公开文献证据进行信息整理，不得使用未提供的外部知识补充结论。

要求：
1. 区分“文档记载”“系统推断”“无法确定”；
2. 每个事实点必须标注 [C1] 等引用；
3. 查询某项权利要求时优先逐字忠实概括，不能改变技术限定；
4. 对多个文档做比较时分别列出各自依据；
5. 不得把“发明专利申请公布”描述为“已授权专利”；
6. 不判断新颖性、创造性、实用性、侵权、无效或授权概率；
7. 文档包含医学、物理效果主张时，只能表述为“该文档记载/声称”，不得背书；
8. 证据不足时回答“根据当前已导入的专利文档无法确定”。
```

### 10.3 安全回答规则

以下问题必须拒绝形成确定性法律结论：

- “这个专利一定能授权吗？”
- “这个方案是否具有创造性？”
- “某产品是否侵权？”
- “这项专利是否有效？”
- “该专利保护范围一定覆盖什么？”

标准回答方向：

```text
当前系统只能基于已导入的公开文献整理其记载内容；该问题需要结合权利要求解释、审查档案、现有技术或具体产品事实，由专业人员判断。
```

---

## 11. 前端 MVP

在当前实际使用的 Vben 前端中修改，不新建第二套前端。

### 11.1 知识库表单

增加：

```text
领域：通用 / 专利
```

值：

```text
GENERAL / PATENT
```

列表增加领域标签。

### 11.2 文档列表和详情

PATENT 文档显示：

- 发明名称；
- 申请号；
- 公布号；
- 申请日；
- 公布日；
- 申请人；
- 发明人；
- IPC；
- 权利要求数量；
- 解析状态；
- 版本状态。

元数据解析失败时显示 `未识别`，不能显示伪造值。

### 11.3 Chunk 页面

增加筛选：

- sectionType；
- claimNo；
- applicationNo；
- publicationNo。

至少可以在弹窗中看到完整 metadata JSON。

### 11.4 问答工作台

- 增加知识库选择器；
- PATENT 知识库一次选择一个；
- 回答底部显示证据卡片；
- 证据卡片可展开原始 Chunk；
- 卡片显示：
  - 文档名；
  - 公布号；
  - 申请号；
  - 章节；
  - 权利要求号；
  - 页码；
  - 版本；
  - 命中通道；
  - Rerank 分数（调试模式）。

---

## 12. 三份演示文档

将用户提供的三份 PDF 作为固定演示数据，不要改写文件内容：

```text
04491152-b19c-46bd-9965-28c50e277e5e.pdf
2023110429811.pdf
2023118322140.pdf
```

对应基准：

### 文档 A

```text
申请号：202311344028.2
公布号：CN 122621758 A
名称：一种分区域视频和图片的储存和下载技术
申请人：韩信
发明人：韩信
权利要求数量：7
```

### 文档 B

```text
申请号：202311042981.1
公布号：CN 122604134 A
名称：一种代替印花的运动服
申请人：辽宁国科科技有限公司
发明人：孙新玲
权利要求数量：3
```

### 文档 C

```text
申请号：202311832214.0
公布号：CN 122619519 A
名称：一种粒子化磁涌装置及其使用方法
申请人：魏民
发明人：魏民
权利要求数量：9
```

---

## 13. 必测问题与验收结果

至少执行以下测试：

1. `申请号 202311344028.2 的发明名称和申请人是什么？`
2. `CN 122621758 A 一共有几项权利要求？`
3. `哪一份文档提出用电脑绣代替印花？`
4. `申请号 202311042981.1 的权利要求1主要限定了什么？`
5. `粒子化磁涌装置的权利要求1包含哪些核心组成？`
6. `申请号 202311832214.0 的权利要求8引用了哪些在先权利要求？`
7. `三份专利分别属于什么技术领域或 IPC 方向？`
8. `哪一份专利涉及视频或图片不同区域以不同分辨率下载？`
9. `这三件专利哪一件已经获得授权？`
10. `粒子化磁涌装置真的能治疗癌症吗？`
11. `对比三份专利独立权利要求的保护对象类型。`
12. `第二份专利中 3wt% 和 1wt% 分别出现在哪些记载中？`

验收要求：

- 1~8、11~12 能检索到正确文档并有精确引用；
- 9 必须说明当前材料是申请公开文本，不能据此确认授权状态；
- 10 只能说明文档存在相关记载，不能认可医学有效性；
- 权利要求查询必须返回对应 claimNo；
- 引用至少精确到文档 + 章节 + 权利要求号或页码；
- 任一答案不得混淆三份文档的申请号、公布号和申请人；
- 不得将申请公布号说成授权公告号。

---

## 14. 批次实施顺序

必须按批次执行。每批编译、测试通过后才能进入下一批。

### Batch A：基线与领域字段

1. 执行：

```bash
git status --short
git branch --show-current
git log -1 --oneline
```

2. 创建分支：

```bash
git switch -c feat/patent-mvp-v0.1
```

3. 阅读：

```text
CONVENTIONS.md
CLAUDE.md
README.md
docs/
```

4. 增加数据库迁移；
5. 增加 `domainCode`、`domainMetadata` 全链路字段；
6. 增加知识库前端领域选择；
7. 编译和测试。

进入 Batch B 的条件：

- 历史 GENERAL 知识库不受影响；
- 新建 PATENT 知识库成功；
- 文档 RPC 能返回 `domainCode`；
- migration 可执行。

### Batch B：专利入库

1. 实现 PatentMetadataExtractor；
2. 实现 PatentClaimParser；
3. 实现 PatentSplitter；
4. 接入 IngestServiceImpl；
5. 持久化 document/domain metadata 和 chunk metadata；
6. PATENT 跳过客服 ReviewItem/Product 抽取，进入文档审核；
7. 为三份 PDF 编写解析和切片测试。

进入 Batch C 的条件：

- 三份 PDF 均能解析；
- 申请号、公布号、名称、申请人、发明人提取正确；
- 权利要求数量分别为 7、3、9；
- 每项权利要求保持完整；
- 文档 C 的权利要求8 `dependsOn` 能覆盖 1~7；
- Chunk 带 section/page/claim metadata。

### Batch C：检索、回答和引用

1. 实现领域 Query/Answer Policy；
2. PATENT Query Analysis；
3. PATENT 跳过产品门禁和客服 Slot；
4. Chat 增加 kbIds 绑定；
5. Evidence 透传专利元数据；
6. 前端显示来源卡片；
7. 完成 12 条核心问题以及 Golden Cases 中 3 条边界问题的人工和自动测试。

进入 Batch D 的条件：

- 编号、权利要求和跨文档问题均能正确召回；
- 引用信息完整；
- 安全拒答符合要求；
- 不搜索未选择知识库。

### Batch D：评测、运行文档和收尾

1. 导入 `patent_mvp_golden_cases.jsonl`；
2. 运行评测；
3. 输出 Recall、MRR/NDCG（若现有模块支持）、Faithfulness、Citation Accuracy；
4. 增加专利 MVP 启动和演示 README；
5. 提供数据库升级、启动、导入、发布、测试步骤；
6. 检查 `git diff`；
7. 不自动 push。

---

## 15. 测试要求

### 15.1 单元测试

至少新增：

```text
PatentMetadataExtractorTest
PatentClaimParserTest
PatentSplitterTest
PatentDomainQueryPolicyTest
PatentAnswerPolicyTest
ChatKbBindingTest
PatentCitationMappingTest
```

必须覆盖：

- 带空格申请号；
- 多 IPC；
- 权利要求跨行；
- 独立/从属权利要求；
- `1至7中任意一项`；
- 缺失字段；
- PDFBox 降级；
- PATENT 产品门禁关闭；
- GENERAL 产品门禁不变；
- 非可见 KB 被过滤；
- 申请公开不等于授权；
- 医疗效果不背书。

### 15.2 集成测试

至少完成：

```text
PDF -> ParsedDocument -> PatentSplitter -> MySQL Chunk
发布 -> ES/Milvus -> Retrieval
Retrieval -> Evidence -> Answer -> Citation
Chat kbIds -> Evidence kbIds -> ACL Filter
```

外部依赖不可用时：

- 使用 Testcontainers、Mock 或项目既有测试方式；
- 明确记录哪些测试真实连了 MySQL/ES/Milvus/模型；
- 不得用“代码看起来没问题”代替测试结果。

### 15.3 构建命令

根据项目实际模块修正，但至少运行：

```bash
mvn -pl yudao-module-knowledge,yudao-module-ingestion,yudao-module-retrieval,yudao-module-evidence,yudao-module-chat -am test
```

以及完整编译：

```bash
mvn -DskipTests compile
```

前端根据实际包管理器运行：

```bash
pnpm install
pnpm typecheck
pnpm build
```

不得只执行单个类编译。

---

## 16. 运行说明必须交付

新增：

```text
docs/patent-mvp/README.md
docs/patent-mvp/DEMO.md
docs/patent-mvp/API.md
docs/patent-mvp/LIMITATIONS.md
```

README 至少说明：

1. 所需中间件；
2. 所需模型；
3. 环境变量；
4. SQL migration；
5. 服务启动顺序；
6. 前端启动；
7. 创建 PATENT 知识库；
8. 上传三份 PDF；
9. 审核和发布；
10. 导入 Golden Cases；
11. 演示问题；
12. 当前限制和法律免责声明。

---

## 17. 代码质量约束

- 不允许推倒重写；
- 不允许复制整套 ingestion/retrieval/evidence；
- 不允许在多个类中散落 `if ("PATENT".equals(...))`；
- 领域分支集中在 Policy/Adapter Registry；
- 不允许 TODO、空实现、固定返回值冒充完成；
- 不允许吞异常后假装成功；
- 不允许自动修改或提交用户未提交文件；
- 不允许提交密钥、Token、模型 API Key；
- 不允许改 `target`；
- 不允许删除 GENERAL 功能；
- 不允许自动执行 `git push`；
- 不允许把三份 PDF 的答案硬编码进业务代码或 Prompt；
- 测试夹具可以引用期望值，但生产实现必须真实解析文档。

---

## 18. 完成定义

只有全部满足才可报告“Patent MVP v0.1 完成”：

- [ ] 新建 PATENT 知识库成功；
- [ ] 三份 PDF 可上传、解析、审核、发布；
- [ ] 著录信息正确；
- [ ] 权利要求切片完整；
- [ ] 权利要求依赖正确；
- [ ] ES 和 Milvus 可检索；
- [ ] Chat 只搜索所选知识库；
- [ ] 回答带专利级来源；
- [ ] 15 条 Golden Cases 全部通过；
- [ ] GENERAL 回归测试通过；
- [ ] 后端测试通过；
- [ ] 前端 typecheck/build 通过；
- [ ] migration 和启动文档齐全；
- [ ] 无密钥泄漏；
- [ ] `git diff --check` 通过；
- [ ] 未自动 push。

---

## 19. 每批报告格式

每完成一个 Batch，必须输出：

1. 验证到的现状问题和代码路径；
2. 修改文件列表；
3. 数据库 migration；
4. 关键类和接口；
5. 状态机或调用链变化；
6. 新增测试；
7. 实际执行命令；
8. 测试结果；
9. 未解决问题；
10. 兼容性和回滚方案；
11. `git diff --stat`；
12. 建议 commit message；
13. 下一批进入条件是否满足。

---

## 20. DeepSeek 执行入口

你现在是本仓库的实施工程师。完整读取本任务书以及仓库中的 `CONVENTIONS.md`、`CLAUDE.md`、README、`docs`，然后直接修改真实源码，不要只输出分析、建议、伪代码或文件清单。

执行规则：

1. 先执行 `git status --short`，保护现有未提交修改；
2. 确认当前分支和最新 commit；
3. 创建 `feat/patent-mvp-v0.1`；
4. 严格按 Batch A -> B -> C -> D 实施；
5. 每批必须编译和测试；
6. 失败时留在当前批次修复，不得跳过；
7. 所有业务实现必须复用现有模块；
8. 三份 PDF 必须通过真实解析，不得硬编码答案；
9. 不自动 push；
10. 最后给出完整运行步骤和演示结果。

现在从 Batch A 开始，先验证本任务书指出的当前代码现状是否与仓库一致；一致后直接实施，不要停留在方案讨论阶段。
