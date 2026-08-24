-- V19: 多轮对话查询上下文(CQ-01~35)
-- ResultSetSnapshot(ai_chat_result_set) + ContextFrame 栈(ai_chat_context_frame) + 会话轻量查询状态(ai_conversation.query_state)
-- 大结果集: 内联(ordered_entity_ids) 或 REF(scope_descriptor + knowledge_revision, 按需 materialize), 禁止无限 ID 塞会话表。

-- 1. ai_conversation 增加轻量查询状态(JSON; 只存引用/计数, 不存大 ID 集)
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND COLUMN_NAME = 'query_state') = 0,
  'ALTER TABLE `ai_conversation` ADD COLUMN `query_state` json NULL DEFAULT NULL COMMENT ''多轮查询状态快照(JSON: lastResultSetId/entityType/entityCount/lastMetric/lastField/lastOperation/lastQueryId/lastUpdatedAt)'' AFTER `context_summary`',
  'SELECT 1');
PREPARE s1 FROM @ddl; EXECUTE s1; DEALLOCATE PREPARE s1;

-- 2. 结果集快照(ResultSetSnapshot): 结构化/实体查询成功后形成, 保序
CREATE TABLE IF NOT EXISTS `ai_chat_result_set` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `result_set_id` varchar(64) NOT NULL COMMENT '结果集编号(rs- 前缀)',
    `query_id` varchar(64) DEFAULT NULL COMMENT '产生它的查询编号(q- 前缀)',
    `conversation_id` bigint DEFAULT NULL COMMENT '会话编号',
    `entity_type` varchar(32) DEFAULT NULL COMMENT '实体类型(如 PATENT_DOCUMENT)',
    `entity_count` int NOT NULL DEFAULT 0 COMMENT '实体总数(逻辑集合完整数)',
    `storage_mode` varchar(16) NOT NULL DEFAULT 'INLINE' COMMENT 'INLINE 内联 ids / REF 仅存描述按需重建',
    `ordered_entity_ids` text COMMENT '保序实体 id 列表(INLINE 时 JSON 数组)',
    `scope_descriptor` text COMMENT '范围描述(JSON: kbId/domainCode/scopeType/filters/sort), REF materialize 用',
    `knowledge_revision` varchar(64) DEFAULT NULL COMMENT '知识修订标记(版本/发布时间, 用于 STALE 判定)',
    `status` varchar(16) NOT NULL DEFAULT 'VALID' COMMENT 'VALID / STALE',
    `truncated` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否截断(结果数超过上限)',
    `valid_value_count` int DEFAULT NULL COMMENT '存在有效字段值的实体数(PARTIAL 统计)',
    `missing_value_count` int DEFAULT NULL COMMENT '缺少字段值的实体数(PARTIAL 统计)',
    `conflict` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否存在同一字段多个当前值冲突',
    `creator` varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_result_set_id` (`result_set_id`),
    KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 多轮查询结果集快照';

-- 3. 上下文帧栈(ContextFrame): 每轮 query 成功后 push 一帧, Resolver 从近到远匹配
CREATE TABLE IF NOT EXISTS `ai_chat_context_frame` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `conversation_id` bigint NOT NULL COMMENT '会话编号',
    `seq` int NOT NULL DEFAULT 0 COMMENT '帧序号(递增)',
    `query_id` varchar(64) DEFAULT NULL COMMENT '查询编号(q- 前缀)',
    `entity_type` varchar(32) DEFAULT NULL COMMENT '实体类型',
    `result_set_id` varchar(64) DEFAULT NULL COMMENT '关联结果集编号',
    `metric_code` varchar(64) DEFAULT NULL COMMENT '指标编码(如 CLAIM_COUNT)',
    `field_code` varchar(64) DEFAULT NULL COMMENT '字段编码(如 PUBLICATION_NO)',
    `operation` varchar(32) DEFAULT NULL COMMENT '聚合运算(COUNT/SUM/AVG/MIN/MAX/NONE)',
    `scope_type` varchar(32) DEFAULT NULL COMMENT '范围类型(CURRENT_KB/PREVIOUS_RESULT_SET/EXPLICIT_ENTITY/DOCUMENT_SET)',
    `query_type` varchar(32) DEFAULT NULL COMMENT '查询类型(EXACT_LOOKUP/LIST/GROUP/AGGREGATE/SORT/TOP_N/SCOPED_RAG)',
    `execution_mode` varchar(32) DEFAULT NULL COMMENT '执行模式(STRUCTURED/PER_ENTITY_SEMANTIC/CROSS_ENTITY_SEMANTIC/HYBRID)',
    `query_text` varchar(200) DEFAULT NULL COMMENT '产生该帧的查询文本(摘要)',
    `creator` varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_seq` (`conversation_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 多轮查询上下文帧';
