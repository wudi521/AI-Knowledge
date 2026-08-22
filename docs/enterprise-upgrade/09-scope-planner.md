# 企业级改造 · 09 业务 Scope 检索硬过滤与 QueryPlanner v1(批次 D2/D3)

> 日期: 2026-08-22 · 对应实施规范 D2/D3

## D2 检索硬过滤(完成)
- Query Analysis 抽取地域/产品 slot: province/city(提示词结构化输出, 未提及 null)
- KnowledgeApi.getKbScopes(kbIds) → Map<kbId, List<KnowledgeScopeDTO>>(批量 RPC)
- SearchService.applyScopeFilter: 查询命中 slot 时, 有 scope 配置的知识库必须匹配
  (城市精确优先于省份; 产品 scope 有配置才过滤; 无 scope 配置知识库不受影响, 兼容现状)
- **过滤后无可用知识库 → 拒绝混合不同地市规则, Abstain 转人工**
- scope RPC 失败降级不过滤+告警(项目 degrade-never-block 原则, 与权限 Fail Closed 区分)

## D3 QueryPlanner v1(轻量规则路由)
- 路由标记 route: OUT_OF_SCOPE→ABSTAIN / 命中地域 slot→SCOPE_FILTER_HYBRID_RAG / 默认 HYBRID_RAG
- 输出到检索响应 AnalysisVO.route(前端检索测试页可展示)
- 规则安全降级: 不依赖 LLM 额外调用(基于 QueryAnalysis 已有产物), 无任意 SQL/URL 生成风险
- 完整 QueryPlan(ENTITY_LOOKUP/GRAPH_TRAVERSAL/RULE/READ_ONLY_SQL/EXTERNAL_API + 版本化) 属批次 E/F 演进

## 文件
- retrieval: QueryAnalysis(province/city)、QueryAnalysisService(提示词+解析)、SearchService(scope过滤+route)、RetrievalRespVO(AnalysisVO.route)
- knowledge: KnowledgeScopeDTO、KnowledgeApi+Impl(getKbScopes)

## 验证
- retrieval/knowledge/evidence 编译通过
- ⚠️ 端到端(省市过滤、Abstain 转人工)受沙箱限制未实测, 部署后按测试矩阵 8 验证

## 回滚
- git revert; applyScopeFilter 移除即恢复不过滤行为
