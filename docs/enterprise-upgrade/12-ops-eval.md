# 企业级改造 · 12 评测闸门增强与运维要求(批次 F4/F6 清单)

> 日期: 2026-08-22 · 对应实施规范 F4/F6

## F4 Evaluation Gate(现状 + 增强清单)
**已有**: 发布闸门 checkGate(评测达标才可发布, 可配置开关) ✅
**增强清单**(后续):
1. 按类型分类统计: Simple QA / Semantic / Entity / Multi-hop / Temporal / Conflict / Permission / Scope / Rule / Abstention / Citation
2. 自动触发回归: 代码/prompt/模型/切片/索引 schema/知识版本变更 → 触发评测(CI 集成)
3. 关键安全类别零泄露; 不能只看总平均分
4. 评测未通过不能发布新配置或知识版本(已有 checkGate 承接)

## F6 企业运维(要求清单, 按优先级)
1. **配额**: 每租户文档/chunk/QPS/并发/token/存储配额(需 ai_tenant_quota 表, 后续)
2. **数据生命周期**: 软删除已有(deleted 位); 彻底擦除/legal hold 待补
3. **备份恢复**: MySQL/ES/Milvus/MinIO 备份与恢复演练 + RPO/RTO 文档(运维手册)
4. **索引滚动**: ES alias + Milvus collection 滚动升级/回滚(批次 C6b 承接)
5. **安全**: SBOM/依赖漏洞扫描/镜像非 root/TLS/Secret 管理(K8s 部署阶段)
6. **管理 API 清单**(待补): 任务查询/重试、DLQ 重放、索引一致性巡检、ACL、实体合并、
   评测、模型健康、成本(已有部分: 模型健康/成本/ACL/评测/图谱)

## 与实施规范测试矩阵对照
| 测试矩阵项 | 承接批次/状态 |
|---|---|
| 1 child.parentId 真实主键 | B2 ✅(两阶段批量+回填) |
| 2/3 Outbox 补发/消费幂等 | C2 ✅ |
| 4 ES成功Milvus失败不发布 | C5 ✅(两阶段发布) |
| 5 并发发布唯一有效 | C5 ✅(原子过期) |
| 6 过期版本不可见 | C5+P0-2 ✅ |
| 7 ACL 不泄露 | D1 ✅(Fail Closed) |
| 8 城市已知不混入 | D2 ✅(scope 硬过滤) |
| 9 ES bulk item 失败 | C6 ✅ |
| 10 API Key 无明文 | A2 ✅ |
| 11 伪造 login-user 拒绝 | A1 ✅ |
| 12 恶意 URL 拒绝 | A3 ✅ |
| 13 claim→文档版本追溯 | F1 ✅ |
| 14 删除后不可检索 | P0-2+C5 ✅ |
| 15 实体合并审计/多跳证据 | E ✅ |
