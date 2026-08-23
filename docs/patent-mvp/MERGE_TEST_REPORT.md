# 专利 MVP v0.1 · 分支合并 + 编译测试 + 运行时验证报告

> 分支: fix/patent-mvp-runtime-p0 · 合并对象: feat/patent-mvp-v0.1 @ 22f066b(框架修改)
> 执行时间: 2026-08-23 · 环境: 本地全链路(网关 48080 + 5 模块)

## 1. merge 冲突列表(6 个文件)

| 文件 | 冲突数 | 合并结果 |
|---|---|---|
| PatentSplitter.java | 3 | 两侧合并(见 2) |
| ChatPipeline.java | 1 | 取 feat(降级回映) |
| AnswerGenerator.java | 1 | 两侧合并(通用提示词 feat + PATENT_SYSTEM_PROMPT HEAD) |
| DomainQueryPolicy.java | 1 | 两侧全部保留(default 化去重) |
| PatentDomainQueryPolicy.java | 2 | 重写合并(PATENT_INTENTS + useKnowledgeBaseIntents=false + enableAutoIntentSummary=false + 完整提示词) |
| QueryAnalysisService.java | 4 | 取 HEAD(ChatGPT 完整版: preParsePatent/applyPatentHints/effectiveIntents/路由 + 领域优先 buildSystemPrompt) |

## 2. 每个冲突如何解决

- **PatentSplitter.matchSection**: 合并 null 防护(HEAD) + `[\s\u3000]+` 全角空格修复(feat) → 章节标题"权　利　要　求　书"可识别
- **PatentSplitter.splitClaims**: 取 feat —— 跳过 ImageElement(图片 contextBefore 含 claim 行, 防重复解析 20chunks→11chunks)
- **PatentSplitter.splitDescription**: 取 feat —— 仅文本元素判章节标题行(图片不误判丢失)
- **PatentSplitter.resolveClaimPageRanges**: 补 ImageElement 跳过(防 contextBefore 污染 claim 页码追踪, K 项 pageStart/pageEnd 与 feat 修复兼容)
- **ChatPipeline.buildCitations**: 取 feat —— claims 为空时从回答 [C1]..[CN] 回映 Evidence(E 项); 保留 HEAD 的"先 createConversation 再 bindKbIds"(J 项, 冲突区外)
- **AnswerGenerator**: 通用 SYSTEM_PROMPT 取 feat(事实点沿用证据原文表述) + 保留 HEAD 的 PATENT_SYSTEM_PROMPT(区分"文献记载/声称"/授权状态/医疗表述, I 项) + isPatentEvidence 路由
- **DomainQueryPolicy**: 保留 HEAD 的 useKnowledgeBaseIntents/supportedIntents + feat 的 enableAutoIntentSummary, 全部 default 化
- **PatentDomainQueryPolicy**: 重写 —— PATENT_INTENTS(public) + 完整查询分析提示词(禁止客服意图/精确标识保留/意图判定明细) + useKnowledgeBaseIntents=false + enableAutoIntentSummary=false
- **QueryAnalysisService**: 取 HEAD(ChatGPT 完整版) —— preParsePatent/effectiveIntents/applyPatentHints(EXACT_METADATA/EXACT_CLAIM/SCOPED_RAG/ABSTAIN 路由标记) + 领域优先 buildSystemPrompt + 领域意图集钳制

## 3. 最终 branch HEAD

```
b501aea merge: 合并 feat/patent-mvp-v0.1 框架修改(22f066b) 到 fix/patent-mvp-runtime-p0
```

(fix 分支 4226115 之上; merge 含 5 个新增测试文件)

## 4. git diff --stat

```
相对 merge base(0d0bfa9): 23 files changed, 789 insertions(+), 54 deletions(-)
```

## 5. Maven compile

```
mvn -pl ingestion,retrieval,evidence,chat,knowledge-server -am compile → EXIT=0(无 ERROR)
```

## 6. Maven test(新增 5 个单测, 18 用例全过)

| 测试 | 用例 | 结果 |
|---|---|---|
| PatentQueryPreParserTest | 7 | ✅ 申请号/公布号/权利要求1/1至7/1、3、5/1或2/无标识 |
| PatentClaimParserTest | 4 | ✅ 独立+从属 range/list/or/跨行合并 |
| ConflictDetectorTest | 3 | ✅ true+无矛盾拦截/同专利同Claim跳过模型/不同Claim不跳过 |
| SufficiencyJudgeTest | 3 | ✅ 单PATENT CLAIM可作答/单PATENT BIBLIO可作答/GENERAL保持原规则 |
| ChatPipelineTest | 1 | ✅ 新会话先createConversation再bindKbIds |

## 7. Maven package

```
mvn package -DskipTests → EXIT=0
```

## 8. 三份 PDF ingest 结果(重建数据后)

| 申请号 | doc | version | PATENT_CLAIM | total_chunks | claimCount 期望 |
|---|---|---|---|---|---|
| 202311344028.2 | 58 | 56 | **7** | 11 | 7 ✅ |
| 202311042981.1 | 56 | 54 | **3** | 9 | 3 ✅ |
| 202311832214.0 | 57 | 55 | **9** | 16 | 9 ✅ |

无重复 claimNo; 依赖正确(202311832214.0: claim2-7→[1], claim8→[1,2,3,4,5,6,7], claim9→[8]);
sourcePageStart/End=2/2, metadata.pageStart/pageEnd=2/2(权利要求页)均落库

## 9. 5 个 Query Case 完整结果

| Case | 问题 | intent | route | answerable | reply | 证据 |
|---|---|---|---|---|---|---|
| 1 | 哪一份文档提出用电脑绣代替印花？ | **DOCUMENT_COMPARISON** | HYBRID_RAG | ✅ | "文档 [C1] 提出用电脑绣代替印花" | 全来自 202311042981.1 |
| 2 | 申请号 202311042981.1 的权利要求1主要限定了什么？ | CLAIM_LOOKUP | HYBRID_RAG(应 EXACT_CLAIM) | ❌ Claim验证失败 | 生成"无法确定"→UNSUPPORTED | claim2/3 + **跨专利 202311344028.2** |
| 3 | 申请号 202311832214.0 的权利要求8引用了哪些在先权利要求？ | CLAIM_DEPENDENCY | HYBRID_RAG(应 EXACT_CLAIM) | ❌ Claim验证失败 | 生成"无法确定"→UNSUPPORTED | claim2/5/6 + **跨专利污染** |
| 4 | 这三件专利哪一件已经获得授权？ | (检索正常) | HYBRID_RAG | ❌ Claim验证失败 | 生成回答→验证 UNSUPPORTED | 7 条 |
| 5 | 粒子化磁涌装置真的能治疗癌症吗？ | **OUT_OF_SCOPE** | ABSTAIN | ❌ 超出知识库范围 | 4.5s 意图层快拒 | 0 |

## 10. LM Studio 的 Patent Query Prompt 日志

LM Studio 为本地模型服务(不落盘), 无法直接取日志; 以运行证据替代:
- CASE 1 intent=**DOCUMENT_COMPARISON**(不再是"合同条款")——PatentDomainQueryPolicy 提示词生效 ✅
- CASE 2 intent=**CLAIM_LOOKUP**(正确); 查询分析器走"你是专利公开文献知识库的查询分析器"(PatentDomainQueryPolicy.queryAnalysisPrompt)

## 11. Conflict 是否还出现 true + 无矛盾

- 单测 ConflictDetectorTest.selfContradictoryTrueWithNoConflictReasonIsIgnored ✅(NON_CONFLICT_REASON_MARKERS 拦截)
- 运行时 5 Case conflict_count 全为 0(含 CASE 2/3 同专利多证据对——跳过或判无冲突)

## 12. Exact Claim 是否还有跨专利污染

**有(确认)**:
- CASE 2 证据 8 条含 202311344028.2 的 claim2/4 + DRAWING/EMBODIMENT(4 条)
- CASE 3 证据 7 条含 202311344028.2/202311042981.1 的 chunks(3 条)

根因: SearchService.resolveRoute 丢弃 analysis.getRoute()(EXACT_CLAIM 被重算为 HYBRID_RAG);
Reranker 结构化 boost 只排序不过滤; 无 applicationNo→documentId 硬过滤。

## 13. 每个 Case 总耗时(chat/send 全链路)

| Case | Total(s) | 说明 |
|---|---|---|
| 1 | 173.7 | QueryAnalysis 3-5s + 检索 ~2s + Generate/Verify 多轮(claim_pass=1 一次通过但验证仍重试?) |
| 2 | 171.5 | QueryAnalysis + 检索 + Generate + Verify 重试耗尽(claim_pass=0) |
| 3 | 118.7 | 同上 |
| 4 | 85.3 | 同上 |
| 5 | 4.5 | 意图 OUT_OF_SCOPE 短路(无检索/生成) |

模型调用观测(ai_model_call_log): qwen3-8b 单次 1.4~32.7s(生成/验证/分析), rerank 0.38s, embedding 0.24s;
Total 主要为 LLM 串行(分析→冲突→生成→验证×重试, 每轮 10-30s)。

## 14. 失败测试和堆栈

- 单测: 无失败(18/18 通过)
- Query Case 2/3/4 失败堆栈(经 ai_evidence_eval.claims 反推): 生成回答 → ClaimVerifier 判 UNSUPPORTED
  (CASE 2/3: 检索缺精确 claim chunk → 生成"无法确定" → 验证 UNSUPPORTED → claimFail)
- CASE 5: intent 层 OUT_OF_SCOPE 短路(SearchService.resolveRoute → ABSTAIN)

## 15. 未解决问题(需继续实现 Patent Exact/Scoped Hard Filter)

按约定: 不继续调 Prompt。最小改造位置:

| 位置 | 改造 |
|---|---|
| **KnowledgeApi** | 提供 applicationNo/publicationNo → documentId 解析(或检索 RPC 支持 documentIds 参数) |
| **SearchService** | 消费 patentHints(applicationNo/claimNos): EXACT_CLAIM → resolve documentId + sectionType=CLAIMS + claimNo 过滤后再检索; EXACT_METADATA → documentId 过滤; SCOPED_RAG → documentId 内混合检索 |
| **resolveRoute** | 透传 analysis.getRoute()(EXACT_METADATA/EXACT_CLAIM/SCOPED_RAG), 不被 HYBRID_RAG 覆盖 |
| **Bm25Searcher/VectorSearcher** | search 增加 documentIds 过滤参数(ES 已有 document_id 字段; Milvus 需加 document_id/claim_no 标量) |
| **ResultFilter** | 检索后按 chunk metadata(applicationNo/claimNo) 二次过滤, 杜绝跨专利/跨 claim 污染 |
| **ES/Milvus metadata** | v0.2 补 domain_code/application_no/claim_no 等 keyword 字段(当前仅 document_id 可用) |
