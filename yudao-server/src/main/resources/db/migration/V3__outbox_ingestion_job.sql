-- C1: 事务性 Outbox 与持久化入库任务(批次 C, Flyway V3)
-- 由迁移执行方(yudao-server)启动时自动执行
-- 回滚: DROP TABLE ai_outbox_event / ai_ingestion_job(见 docs/enterprise-upgrade/06-outbox-ingestion-job.md)

-- ① Outbox 事件表(knowledge 模块: 文档创建等业务变更, 与业务同事务提交, 由 Publisher 可靠发送 Kafka)
CREATE TABLE IF NOT EXISTS `ai_outbox_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型: DOCUMENT',
  `aggregate_id` bigint NOT NULL COMMENT '聚合编号(如文档编号)',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型: DOCUMENT_CREATED',
  `payload` json NULL COMMENT '事件载荷',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/SENT/FAILED',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '发送重试次数',
  `sent_at` datetime NULL DEFAULT NULL COMMENT '发送成功时间',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_aggregate_event` (`tenant_id`, `aggregate_type`, `aggregate_id`, `event_type`),
  INDEX `idx_status_create` (`status`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '事务性 Outbox 事件';

-- ② 入库任务表(ingestion 模块: 文档入库持久化任务状态机, 消费端幂等/断点续跑)
CREATE TABLE IF NOT EXISTS `ai_ingestion_job` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `document_id` bigint NOT NULL COMMENT '文档编号',
  `version_id` bigint NULL DEFAULT NULL COMMENT '版本编号',
  `job_type` varchar(32) NOT NULL DEFAULT 'INGEST' COMMENT '任务类型',
  `stage` varchar(32) NOT NULL DEFAULT 'FETCH' COMMENT '阶段: FETCH/VALIDATE/PARSE/STRUCTURE/CHUNK/EMBED/PERSIST/REVIEW_PREPARE/DONE/FAILED',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/SUCCEEDED/FAILED/RETRYING',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键(默认 documentId)',
  `payload_hash` varchar(64) NULL DEFAULT NULL COMMENT '载荷哈希',
  `total` int NOT NULL DEFAULT 0 COMMENT '总量(如 chunk 数)',
  `progress` int NOT NULL DEFAULT 0 COMMENT '进度',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '重试次数',
  `max_retry` int NOT NULL DEFAULT 3 COMMENT '最大重试',
  `next_retry_time` datetime NULL DEFAULT NULL COMMENT '下次重试时间',
  `lease_owner` varchar(64) NULL DEFAULT NULL COMMENT '租约持有者',
  `lease_expire_at` datetime NULL DEFAULT NULL COMMENT '租约过期时间',
  `error_code` varchar(64) NULL DEFAULT NULL COMMENT '错误码',
  `error_message` varchar(500) NULL DEFAULT NULL COMMENT '错误信息',
  `trace_id` varchar(64) NULL DEFAULT NULL COMMENT '链路追踪号',
  `started_at` datetime NULL DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime NULL DEFAULT NULL COMMENT '完成时间',
  `optimistic_version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_doc_type` (`tenant_id`, `document_id`, `job_type`),
  INDEX `idx_status_retry` (`status`, `next_retry_time`),
  INDEX `idx_tenant_stage` (`tenant_id`, `stage`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '入库持久化任务';
