-- V10: Knowledge Ops Trace 数据模型(总任务书 §31; 缺失才加, 已有同义能力扩展不复建)
-- ① ai_ingestion_job 扩展(kb_id/domain_code; trace_id 已有)
-- ② ai_ingestion_task 新增(入库阶段级 trace)
-- ③ ai_retrieval_trace 扩展(conversation_id/domain_code/tokens; 对应规格 ai_query_trace 复用)
-- ④ ai_query_stage 新增(查询阶段级 trace)
-- 回滚: DROP TABLE ai_query_stage/ai_ingestion_task; 字段逐项 DROP

-- ①
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_ingestion_job' AND COLUMN_NAME = 'kb_id') = 0,
  'ALTER TABLE `ai_ingestion_job` ADD COLUMN `kb_id` bigint NULL DEFAULT NULL COMMENT ''知识库编号'' AFTER `document_id`',
  'SELECT 1');
PREPARE s1 FROM @ddl; EXECUTE s1; DEALLOCATE PREPARE s1;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_ingestion_job' AND COLUMN_NAME = 'domain_code') = 0,
  'ALTER TABLE `ai_ingestion_job` ADD COLUMN `domain_code` varchar(32) NULL DEFAULT ''GENERAL'' COMMENT ''领域代码'' AFTER `kb_id`',
  'SELECT 1');
PREPARE s2 FROM @ddl; EXECUTE s2; DEALLOCATE PREPARE s2;

-- ② 入库阶段级 trace
CREATE TABLE IF NOT EXISTS `ai_ingestion_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `job_id` bigint NOT NULL COMMENT '入库任务编号',
  `stage_code` varchar(32) NOT NULL COMMENT '阶段: FETCH/VALIDATE/PARSE/STRUCTURE/METADATA/CHUNK/EMBED/PERSIST/REVIEW_PREPARE/DONE',
  `stage_order` int NOT NULL DEFAULT 0 COMMENT '阶段顺序',
  `handler` varchar(128) NULL DEFAULT NULL COMMENT '处理器(如 PatentMetadataExtractor)',
  `handler_version` varchar(64) NULL DEFAULT NULL COMMENT '处理器版本',
  `attempt` int NOT NULL DEFAULT 1 COMMENT '尝试次数',
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING' COMMENT '状态: RUNNING/SUCCEEDED/FAILED',
  `input_summary_json` text NULL COMMENT '输入摘要(JSON)',
  `output_summary_json` text NULL COMMENT '输出摘要(JSON)',
  `metrics_json` text NULL COMMENT '指标(JSON: 耗时/数量/维度等)',
  `payload_ref` varchar(256) NULL DEFAULT NULL COMMENT '负载引用(文件路径/批次号)',
  `error_code` varchar(64) NULL DEFAULT NULL COMMENT '错误码',
  `error_message` varchar(500) NULL DEFAULT NULL COMMENT '错误信息',
  `started_at` datetime NULL DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  INDEX `idx_job` (`tenant_id`, `job_id`),
  INDEX `idx_stage` (`tenant_id`, `stage_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '入库阶段级 Trace';

-- ③ ai_retrieval_trace 扩展(对应规格 ai_query_trace 复用)
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_retrieval_trace' AND COLUMN_NAME = 'conversation_id') = 0,
  'ALTER TABLE `ai_retrieval_trace` ADD COLUMN `conversation_id` bigint NULL DEFAULT NULL COMMENT ''会话编号'' AFTER `trace_id`',
  'SELECT 1');
PREPARE s3 FROM @ddl; EXECUTE s3; DEALLOCATE PREPARE s3;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_retrieval_trace' AND COLUMN_NAME = 'domain_code') = 0,
  'ALTER TABLE `ai_retrieval_trace` ADD COLUMN `domain_code` varchar(32) NULL DEFAULT ''GENERAL'' COMMENT ''领域代码'' AFTER `route`',
  'SELECT 1');
PREPARE s4 FROM @ddl; EXECUTE s4; DEALLOCATE PREPARE s4;

-- ④ 查询阶段级 trace
CREATE TABLE IF NOT EXISTS `ai_query_stage` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `trace_id` varchar(64) NOT NULL COMMENT '查询链路追踪号',
  `stage_code` varchar(32) NOT NULL COMMENT '阶段: QUERY_ANALYSIS/REWRITE/SCOPE/BM25/VECTOR/FUSION/RERANK/EVIDENCE/GENERATE/VERIFY',
  `stage_order` int NOT NULL DEFAULT 0 COMMENT '阶段顺序',
  `handler` varchar(128) NULL DEFAULT NULL COMMENT '处理器',
  `handler_version` varchar(64) NULL DEFAULT NULL COMMENT '处理器版本',
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING' COMMENT '状态: RUNNING/SUCCEEDED/FAILED/SKIPPED',
  `input_summary_json` text NULL COMMENT '输入摘要(JSON)',
  `output_summary_json` text NULL COMMENT '输出摘要(JSON)',
  `metrics_json` text NULL COMMENT '指标(JSON)',
  `payload_ref` varchar(256) NULL DEFAULT NULL COMMENT '负载引用(候选chunk列表等)',
  `error_code` varchar(64) NULL DEFAULT NULL COMMENT '错误码',
  `error_message` varchar(500) NULL DEFAULT NULL COMMENT '错误信息',
  `started_at` datetime NULL DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  INDEX `idx_trace` (`tenant_id`, `trace_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '查询阶段级 Trace';
