-- V17: 统一 Query Trace(ai_query_trace 主表 + ai_query_trace_stage 阶段表)
-- P0-09: 每个用户问题一个主 traceId(q- 前缀), 全链路阶段挂在阶段表, 供 Workbench "查看本次执行链路"。

CREATE TABLE IF NOT EXISTS `ai_query_trace` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `trace_id` varchar(64) NOT NULL COMMENT '统一主追踪号(q- 前缀)',
    `conversation_id` bigint DEFAULT NULL COMMENT '会话编号',
    `message_id` bigint DEFAULT NULL COMMENT '消息编号',
    `query` varchar(500) DEFAULT NULL COMMENT '用户问题',
    `route` varchar(32) DEFAULT NULL COMMENT '检索路由',
    `kb_id` bigint DEFAULT NULL COMMENT '知识库编号',
    `domain_code` varchar(32) DEFAULT NULL COMMENT '知识领域编码',
    `total_ms` bigint DEFAULT NULL COMMENT '整体耗时(ms)',
    `status` varchar(16) DEFAULT NULL COMMENT '状态: SUCCEEDED/FAILED/DEGRADED/TIMEOUT',
    `started_at` datetime DEFAULT NULL COMMENT '开始时间',
    `finished_at` datetime DEFAULT NULL COMMENT '结束时间',
    `creator` varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trace_id` (`trace_id`),
    KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 查询 Trace 主表';

CREATE TABLE IF NOT EXISTS `ai_query_trace_stage` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `trace_id` varchar(64) NOT NULL COMMENT '统一主追踪号',
    `seq` int NOT NULL DEFAULT 0 COMMENT '阶段顺序',
    `stage` varchar(32) NOT NULL COMMENT '阶段编码(ANALYZE/ROUTE/BM25/VECTOR/FUSION/RERANK/EVIDENCE/GENERATE/VERIFY/REPAIR 等)',
    `status` varchar(16) DEFAULT NULL COMMENT '状态: SUCCEEDED/FAILED/SKIPPED',
    `elapsed_ms` bigint DEFAULT NULL COMMENT '耗时(ms)',
    `skipped` bit(1) DEFAULT b'0' COMMENT '是否跳过',
    `error_code` varchar(64) DEFAULT NULL COMMENT '错误码',
    `error_message` varchar(500) DEFAULT NULL COMMENT '错误信息(脱敏)',
    `model_call_id` varchar(64) DEFAULT NULL COMMENT '模型调用编号',
    `input_summary` varchar(1000) DEFAULT NULL COMMENT '输入摘要(不含敏感内容)',
    `output_summary` varchar(1000) DEFAULT NULL COMMENT '输出摘要(不含敏感内容)',
    `creator` varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
    PRIMARY KEY (`id`),
    KEY `idx_trace_id` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 查询 Trace 阶段表';
