-- V20: 修复多轮查询上下文运行时表结构与代码模型不一致
-- 目标：即使 V19 已执行，也可前向补齐；即使微服务环境未执行 V19，本脚本单独执行也能建立完整上下文表。

-- 1. ai_conversation.query_state：微服务独立运行时也要保证存在
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND COLUMN_NAME = 'query_state') = 0,
  'ALTER TABLE `ai_conversation` ADD COLUMN `query_state` json NULL DEFAULT NULL COMMENT ''多轮查询状态快照(JSON)'' AFTER `context_summary`',
  'SELECT 1');
PREPARE s1 FROM @ddl; EXECUTE s1; DEALLOCATE PREPARE s1;

-- 2. ResultSetSnapshot：完整结构，包含代码 DO 已依赖的 kb_id/domain_code
CREATE TABLE IF NOT EXISTS `ai_chat_result_set` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `result_set_id` varchar(64) NOT NULL COMMENT '结果集编号(rs- 前缀)',
    `query_id` varchar(64) DEFAULT NULL COMMENT '产生它的查询编号(q- 前缀)',
    `conversation_id` bigint DEFAULT NULL COMMENT '会话编号',
    `kb_id` bigint DEFAULT NULL COMMENT '知识库编号',
    `domain_code` varchar(32) DEFAULT NULL COMMENT '领域编码',
    `entity_type` varchar(32) DEFAULT NULL COMMENT '实体类型',
    `entity_count` int NOT NULL DEFAULT 0 COMMENT '实体总数',
    `storage_mode` varchar(16) NOT NULL DEFAULT 'INLINE' COMMENT 'INLINE/REF',
    `ordered_entity_ids` text COMMENT '保序实体 ID 列表(JSON)',
    `scope_descriptor` text COMMENT '范围描述(JSON)',
    `knowledge_revision` varchar(64) DEFAULT NULL COMMENT '知识修订标记',
    `status` varchar(16) NOT NULL DEFAULT 'VALID' COMMENT 'VALID/STALE',
    `truncated` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否截断',
    `valid_value_count` int DEFAULT NULL COMMENT '有效字段值实体数',
    `missing_value_count` int DEFAULT NULL COMMENT '缺失字段值实体数',
    `conflict` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否冲突',
    `creator` varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_result_set_id` (`result_set_id`),
    KEY `idx_query_id` (`query_id`),
    KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 多轮查询结果集快照';

-- 3. 已执行 V19 的库：补齐缺失列
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_chat_result_set' AND COLUMN_NAME = 'kb_id') = 0,
  'ALTER TABLE `ai_chat_result_set` ADD COLUMN `kb_id` bigint DEFAULT NULL COMMENT ''知识库编号'' AFTER `conversation_id`',
  'SELECT 1');
PREPARE s2 FROM @ddl; EXECUTE s2; DEALLOCATE PREPARE s2;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_chat_result_set' AND COLUMN_NAME = 'domain_code') = 0,
  'ALTER TABLE `ai_chat_result_set` ADD COLUMN `domain_code` varchar(32) DEFAULT NULL COMMENT ''领域编码'' AFTER `kb_id`',
  'SELECT 1');
PREPARE s3 FROM @ddl; EXECUTE s3; DEALLOCATE PREPARE s3;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_chat_result_set' AND INDEX_NAME = 'idx_query_id') = 0,
  'ALTER TABLE `ai_chat_result_set` ADD INDEX `idx_query_id` (`query_id`)',
  'SELECT 1');
PREPARE s4 FROM @ddl; EXECUTE s4; DEALLOCATE PREPARE s4;

-- 4. ContextFrame：如果微服务环境未执行 V19，则在此兜底创建
CREATE TABLE IF NOT EXISTS `ai_chat_context_frame` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `conversation_id` bigint NOT NULL COMMENT '会话编号',
    `seq` int NOT NULL DEFAULT 0 COMMENT '帧序号',
    `query_id` varchar(64) DEFAULT NULL COMMENT '查询编号',
    `entity_type` varchar(32) DEFAULT NULL COMMENT '实体类型',
    `result_set_id` varchar(64) DEFAULT NULL COMMENT '关联结果集编号',
    `metric_code` varchar(64) DEFAULT NULL COMMENT '指标编码',
    `field_code` varchar(64) DEFAULT NULL COMMENT '字段编码',
    `operation` varchar(32) DEFAULT NULL COMMENT '聚合运算',
    `scope_type` varchar(32) DEFAULT NULL COMMENT '范围类型',
    `query_type` varchar(32) DEFAULT NULL COMMENT '查询类型',
    `execution_mode` varchar(32) DEFAULT NULL COMMENT '执行模式',
    `query_text` varchar(200) DEFAULT NULL COMMENT '查询文本摘要',
    `creator` varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_seq` (`conversation_id`, `seq`),
    KEY `idx_result_set_id` (`result_set_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 多轮查询上下文帧';
