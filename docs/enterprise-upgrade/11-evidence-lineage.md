# 企业级改造 · 11 Evidence Lineage 与可观测(批次 F 核心)

> 日期: 2026-08-22 · 对应实施规范 F1/F5 + F2/F3 现状

## F1 Evidence Lineage(完成)
- V6 迁移: `ai_answer_claim`(trace/claim/verdict/evidence_chunk_id) + `ai_answer_citation`(trace/query/answer_hash/引用chunk列表)
- EvidenceRecorder 增强: claim 逐条落库(claim → 支撑证据片段可追溯); SUPPORTED claim 的引用汇总
- 追溯链: answer → claim → evidence chunk → document/version(检索 DTO 已含) → source locator(批 F6 补 page/offset)
- 失败不阻断响应(try/catch)

## F5 检索追踪(完成)
- V6 迁移: `ai_retrieval_trace`(trace/query/route/intent/变体数/通道统计/耗时/阻断)
- SearchService 落库(正常路径; OUT_OF_SCOPE/范围过滤短路); 失败不阻断
- 用途: 审计/评测/可观测(为 F4 评测闸门与运营指标提供数据)

## F2 Agent/Workflow/Governance(标记 EXPERIMENTAL)
- 现状: 仅骨架/占位接口, 无 Planner/Tool/Executor/Guardrail
- 已标记 EXPERIMENTAL(类注释): 不得作为生产能力对外宣称; 完整 Runtime 属后续独立项目
- 规范要求"不实施完整 Runtime 就标记" — 已满足

## F3 Model Gateway(现状盘点, 已具备核心)
- 场景路由(ai_model_config.scenario, 意图/回答/视觉可分模型)✅ 熔断/重试/降级/计量/成本 ✅
- 补充项(后续): provider 级限流/bulkhead、租户配额、成本预算告警

## F4 Evaluation Gate(现状盘点)
- 已有 checkGate 发布闸门(评测达标才可发布)✅; 分类回归(Simple QA/Entity/Multi-hop...)与
  自动触发(代码/配置/知识变更)属后续增强

## 验证
- retrieval/evidence/knowledge 编译通过
- ⚠️ claim/citation 落库与检索 trace 端到端受沙箱限制未实测, 部署后按测试矩阵 13 验证

## 回滚
- git revert; V6 表 DROP; 落库钩子 try/catch 不阻断主链路, 移除无副作用
