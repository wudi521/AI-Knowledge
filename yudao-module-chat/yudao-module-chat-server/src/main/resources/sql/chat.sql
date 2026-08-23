-- ----------------------------
-- AI 会话
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_conversation`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `channel` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'WEB' COMMENT '渠道: WEB/WECHAT/DINGTALK/APP',
  `customer_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '客户标识',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/TRANSFERRED/CLOSED',
  `intent` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '最后意图',
  `summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '转人工交接摘要',
  `transfer_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '转人工原因',
  `context_summary` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '会话上下文摘要(近轮对话要点)',
  `operator_id` bigint NULL DEFAULT NULL COMMENT '接管坐席编号',
  `message_count` int NOT NULL DEFAULT 0 COMMENT '消息计数',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 会话';

-- ----------------------------
-- AI 消息
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `conversation_id` bigint NOT NULL COMMENT '会话编号',
  `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色: USER/AI/SYSTEM',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '内容',
  `citations` json NULL DEFAULT NULL COMMENT '引用证据编号列表',
  `intent` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '消息意图',
  `entities` json NULL DEFAULT NULL COMMENT '实体列表',
  `confidence` decimal(5,4) NULL DEFAULT NULL COMMENT '证据充分度',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '证据链路追踪号',
  `query_trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '统一主追踪号(q- 前缀, AI 消息)',
  `route` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '权威检索路由(AI 消息)',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_conv`(`conversation_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 消息';

-- ----------------------------
-- AI 回答反馈(与 yudao-server V18 迁移保持一致)
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_chat_feedback`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `message_id` bigint NOT NULL COMMENT '被反馈的 AI 消息编号(ai_message.id)',
  `conversation_id` bigint NULL DEFAULT NULL COMMENT '会话编号',
  `query_trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '统一主追踪号(q- 前缀)',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '证据评估链路追踪号(ev- 前缀)',
  `user_id` bigint NULL DEFAULT NULL COMMENT '反馈用户编号',
  `kb_id` bigint NULL DEFAULT NULL COMMENT '知识库编号',
  `domain_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '知识领域编码',
  `rating` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评价: HELPFUL 有用/NOT_HELPFUL 无用',
  `reason_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '无用原因',
  `comment` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备注',
  `route` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '回答路由',
  `intent` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '意图',
  `confidence` decimal(6,4) NULL DEFAULT NULL COMMENT '置信度(0~1)',
  `latency_ms` bigint NULL DEFAULT NULL COMMENT '回答耗时(ms)',
  `model_id` bigint NULL DEFAULT NULL COMMENT '模型编号',
  `prompt_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '提示词版本',
  `primary_document_id` bigint NULL DEFAULT NULL COMMENT '主证据文档编号',
  `evidence_snapshot` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '证据快照(JSON, 反馈时点)',
  `eval_case_id` bigint NULL DEFAULT NULL COMMENT '点踩生成的评测用例编号',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_message_id`(`message_id`) USING BTREE,
  INDEX `idx_query_trace_id`(`query_trace_id` ASC) USING BTREE,
  INDEX `idx_kb_time`(`kb_id` ASC, `create_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 回答反馈';

