# 企业级改造 · 06 Outbox 与持久化入库任务(批次 C1/C2/C3)

> 日期: 2026-08-22 · 对应实施规范 C1/C2/C3

## C1 持久化任务模型(Flyway V3)
- `ai_outbox_event`: 事务性 Outbox(聚合类型/幂等键/状态/重试)
- `ai_ingestion_job`: 入库任务(文档/版本/阶段/状态/幂等键/重试/租约/错误/追踪/乐观锁)
- 唯一约束: outbox(tenant,aggregate,event); job(tenant,document,job_type)

## C2 Outbox 与消息幂等
- 文档创建: 文档 + DRAFT 版本 + Outbox 事件 **同事务**(@Transactional), 事务内不发 Kafka
- afterCommit 由 OutboxService 发送(同步发送失败保留 FAILED/PENDING)
- @Scheduled 每分钟补偿扫描补发(至少一次语义); 消费端幂等去重
- 测试矩阵2/3 覆盖: 重复消费不重复生成 chunk(消费端 isSucceeded/getOrCreate 拦截)

## C3 入库 DAG 与分批处理
- 任务状态机: PENDING→RUNNING→(FETCH→PARSE→CHUNK→EMBED→PERSIST)→DONE/FAILED
- embedding 分批(默认 32, 可配), 校验返回数量与输入一致(防错位落库)
- 失败置 FAILED + 错误信息(可重试; 重复消息跳过)

## 文件
- knowledge: AiOutboxEventDO/Mapper、OutboxService、Producer 同步发送、AiDocumentServiceImpl 事务化、@EnableScheduling
- ingestion: AiIngestionJobDO/Mapper、IngestionJobService(Impl)、Consumer 幂等、IngestServiceImpl 阶段推进+分批 embedding
- V3 迁移 + yaml batch-size

## 验证
- 编译通过(knowledge+ingestion)
- ⚠️ 连库/端到端(重复消息去重、Outbox 补发)受沙箱限制未实测, 部署后按测试矩阵 2/3 验证

## 回滚
- git revert; 停用 @EnableScheduling 即停补偿; 表 DROP(见 V3 注释)

## 批次 C 剩余
- C4 向量持久化策略(JSON→artifact, 迁移期双读)
- C5 两阶段发布(IndexJob + STAGING generation + 事务置 PUBLISHED)
- C6 ES/Milvus v2 索引字段与 Reconcile
