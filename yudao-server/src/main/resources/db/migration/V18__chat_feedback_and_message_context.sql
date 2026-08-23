-- V18: AI 消息上下文列(query_trace_id/route) + 回答反馈表(ai_chat_feedback)
-- P0-11: 反馈自动关联 Query/Trace/Route/Evidence; message 增加 q- 主 traceId 与权威 route,
-- 供反馈与服务端校验复用。旧 ai_feedback(THUMB_UP/THUMB_DOWN 雏形, 无线上数据) 由 ai_chat_feedback 取代。

-- 1. ai_message 增加上下文列(供反馈自动关联与服务端校验)
ALTER TABLE `ai_message`
    ADD COLUMN `query_trace_id` varchar(64) DEFAULT NULL COMMENT '统一主追踪号(q- 前缀, AI 消息)',
    ADD COLUMN `route` varchar(32) DEFAULT NULL COMMENT '权威检索路由(RULE/EXACT_METADATA/EXACT_CLAIM/SCOPED_RAG/HYBRID_RAG/ABSTAIN, AI 消息)';

-- 2. 回答反馈表(每条 AI 消息唯一当前反馈; 自动关联 query/trace/route/evidence 上下文)
CREATE TABLE IF NOT EXISTS `ai_chat_feedback` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `message_id` bigint NOT NULL COMMENT '被反馈的 AI 消息编号(ai_message.id)',
    `conversation_id` bigint DEFAULT NULL COMMENT '会话编号',
    `query_trace_id` varchar(64) DEFAULT NULL COMMENT '统一主追踪号(q- 前缀)',
    `trace_id` varchar(64) DEFAULT NULL COMMENT '证据评估链路追踪号(ev- 前缀)',
    `user_id` bigint DEFAULT NULL COMMENT '反馈用户编号',
    `kb_id` bigint DEFAULT NULL COMMENT '知识库编号',
    `domain_code` varchar(32) DEFAULT NULL COMMENT '知识领域编码',
    `rating` varchar(16) NOT NULL COMMENT '评价: HELPFUL 有用 / NOT_HELPFUL 无用',
    `reason_code` varchar(32) DEFAULT NULL COMMENT '无用原因: WRONG_ANSWER/NOT_ANSWERED/WRONG_EVIDENCE/INCOMPLETE/OUTDATED_KNOWLEDGE/TOO_VERBOSE/TOO_SLOW/OTHER',
    `comment` varchar(1000) DEFAULT NULL COMMENT '备注(用户输入, 需租户隔离与内容审计)',
    `route` varchar(32) DEFAULT NULL COMMENT '回答路由',
    `intent` varchar(64) DEFAULT NULL COMMENT '意图',
    `confidence` decimal(6,4) DEFAULT NULL COMMENT '证据充分度融合置信度(0~1)',
    `latency_ms` bigint DEFAULT NULL COMMENT '回答耗时(ms)',
    `model_id` bigint DEFAULT NULL COMMENT '模型编号',
    `prompt_version` varchar(64) DEFAULT NULL COMMENT '提示词版本',
    `primary_document_id` bigint DEFAULT NULL COMMENT '主证据文档编号',
    `evidence_snapshot` text COMMENT '证据快照(JSON, 反馈时点), 供 Bad Case 复现',
    `eval_case_id` bigint DEFAULT NULL COMMENT '点踩生成的评测用例编号',
    `creator` varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
    KEY `idx_query_trace_id` (`query_trace_id`),
    KEY `idx_kb_time` (`kb_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 回答反馈';

-- 3. 旧雏形反馈表由 ai_chat_feedback 取代(无线上数据, 删除避免双轨)
DROP TABLE IF EXISTS `ai_feedback`;
