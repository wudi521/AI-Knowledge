# 企业级改造 · 07 两阶段发布与索引增强(批次 C5/C6)

> 日期: 2026-08-22 · 对应实施规范 C5/C6

## C5 两阶段发布
- publish 不再整体 @Transactional("事务内禁远程 ES/Milvus"):
  1. **校验门禁**(无事务): 状态/权限/审核/冲突/空版本/评测闸门(冲突检测 REQUIRES_NEW 独立持久化)
  2. **事务外索引**: ingestionApi.indexVersion 写 ES/Milvus(幂等覆盖式); **失败不置 PUBLISHED, 旧版本继续服务**
  3. **短事务状态流转**: version→PUBLISHED + expireOldVersions(纯 SQL 状态过期) + 文档→PUBLISHED
  4. **事务外级联清理**: cleanupExpiredVersionIndexes(被过期版本 chunk 置 DISABLED + ES/Milvus 删除)
  5. 意图/槽位总结直接触发(无活跃事务)
- 测试矩阵4/5 覆盖: ES 成功 Milvus 失败→不 PUBLISHED; 并发发布仅一个进入有效状态(单条 UPDATE 原子过期)

## C6 索引字段与写入校验
- **ES**: mapping 增加 document_id/version_id/parent_id/chunk_role(keyword/long);
  indexVersion 写入携带 versionId/documentId/chunkRole(dynamic mapping, 存量索引自动收新字段, 无需重建)
- **ES bulk 响应解析**: HTTP 200 但 item 级 error 必须识别(countBulkFailures), 有失败即抛异常可重试
- **Milvus 写入校验**: 数量一致 + 向量维度==dim(1024), 不匹配抛异常阻断(防错位/维度错误静默写入)
- indexVersion 签名扩展 documentId(调用方 knowledge 2 处同步)

## 批次 C 剩余(下一阶段, 大运维改造)
- C4 向量持久化: ai_chunk.embedding JSON → float32 artifact/BLOB(迁移期双读, 千万级 chunk 时执行)
- C6b Milvus v2 collection(version_id 标量): 双写 + backfill + 读开关切换, 再下线旧 collection
- C6c Reconcile 定时巡检: MySQL 事实源 vs ES/Milvus 数量/ID 对比与修复

## 文件
- AiDocVersionServiceImpl(publish 三段式/expireOldVersions 纯状态/cleanupExpiredVersionIndexes)
- IngestionApi+Impl(indexVersion+documentId)、EsChunkStore(mapping+bulk解析)、MilvusChunkStore(校验)

## 验证
- 编译通过(knowledge/ingestion/retrieval)
- ⚠️ 端到端(发布失败旧版本继续服务等)受沙箱限制未实测, 部署后按测试矩阵 4/5 验证

## 回滚
- git revert; 发布链路回归 @Transactional 单事务版本
