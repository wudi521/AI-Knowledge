# 企业级改造 · 08 分层 ACL 与业务 Scope(批次 D1/D2)

> 日期: 2026-08-22 · 对应实施规范 D1/D2

## D1 企业级资源 ACL(已完成)
- `ai_resource_acl`(Flyway V4): resourceType(KB/DOCUMENT/CHUNK/ENTITY)/resourceId/
  subjectType(USER/ROLE/DEPT/ORG/ALL)/action/effect(ALLOW/DENY)/inherit/有效期
- 规则: **DENY 优先于 ALLOW**; 显式 ACL 存在时以其为准, 无记录回退 visible_roles 兼容(迁移兼容)
- Fail Closed: ACL 判定/角色解析异常 → 保守拒绝, 不泄露
- 超管明确绕过(可审计), 无登录态内部调用直通(RPC 契约显式传租户)
- 集成: KnowledgePermissionHelper.isKbVisibleToUser/filterVisibleKbs 叠加 ACL 三层过滤
  (visible_roles 兼容 → 逐条 ACL → 批量 ALL-DENY)
- 管理接口: /knowledge/acl/{create,update,delete,page}(权限 knowledge:acl:*)
- 文件: AiResourceAclDO/Mapper、KnowledgeAclService、KnowledgePermissionHelper、AclController+VO

## D2 业务 Scope(数据模型完成, 检索过滤钩子下一阶段)
- `ai_knowledge_scope`(Flyway V4): kbId/scopeType(PROVINCE/CITY/PRODUCT/CHANNEL/CUSTOMER_SEGMENT)/
  scopeCode/priority/有效期; 唯一约束(kb,type,code)
- DO/Mapper 就绪(按 kbIds 批量/按 type+code 查询)
- 下一阶段: Query Analysis 抽取 province/product slot → SearchService 命中 slot 时按 scope 硬过滤
  (精确城市>省级>全国优先级) + scope 管理接口

## D3 Query Planner(下一阶段)
- QueryPlan 路由(HYBRID_RAG/ENTITY_LOOKUP/RULE/...) + LLM 失败规则降级 + 超范围 Abstain

## 验证
- knowledge/retrieval/evidence 编译通过
- ⚠️ 权限端到端(ACL DENY 生效等)受沙箱限制未实测, 部署后按测试矩阵 7 验证

## 回滚
- git revert; ACL 判定异常回退 visible_roles(删除 aclService 调用); 表 DROP(见 V4 注释)
