-- ----------------------------
-- AI 评测任务
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_eval_task`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `suite_id` bigint NULL DEFAULT NULL COMMENT '测试集编号',
  `model` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '模型',
  `prompt_ver` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'Prompt 版本',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RUNNING' COMMENT '状态: RUNNING/DONE/FAILED',
  `metrics` json NULL DEFAULT NULL COMMENT '指标快照',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `kb_id` bigint NULL DEFAULT NULL COMMENT '评测知识库',
  `case_count` int NULL DEFAULT NULL COMMENT '考题数',
  `start_time` datetime NULL DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `gate_pass` tinyint NULL DEFAULT NULL COMMENT '闸门是否通过',
  `fail_cases` json NULL DEFAULT NULL COMMENT '失败用例明细',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 评测任务';

-- ----------------------------
-- AI 评测用例
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_eval_case`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '问题',
  `gold_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '标准答案',
  `gold_chunks` json NULL DEFAULT NULL COMMENT '标准证据',
  `source_feedback` bigint NULL DEFAULT NULL COMMENT '来源反馈编号',
  `kb_id` bigint NULL DEFAULT NULL COMMENT '知识库编号',
  `category` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '分类(如 综合/保修/收费)',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 评测用例';

-- ----------------------------
-- AI 评测逐题结果
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_eval_result`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `task_id` bigint NOT NULL COMMENT '评测任务编号',
  `case_id` bigint NOT NULL COMMENT '考题编号',
  `answerable` tinyint NOT NULL DEFAULT 0 COMMENT '是否可作答',
  `confidence` decimal(5,4) NOT NULL DEFAULT 0.0000 COMMENT '充分度',
  `recall_at_5` decimal(5,4) NOT NULL DEFAULT 0.0000 COMMENT 'Recall@5',
  `mrr` decimal(5,4) NOT NULL DEFAULT 0.0000 COMMENT 'MRR',
  `ndcg` decimal(5,4) NOT NULL DEFAULT 0.0000 COMMENT 'NDCG@5',
  `faithfulness` decimal(5,4) NOT NULL DEFAULT 0.0000 COMMENT '忠实度',
  `hallucination_rate` decimal(5,4) NOT NULL DEFAULT 0.0000 COMMENT '幻觉率',
  `citation_accuracy` decimal(5,4) NOT NULL DEFAULT 0.0000 COMMENT '引用准确率',
  `passed` tinyint NOT NULL DEFAULT 0 COMMENT '是否达标',
  `fail_reasons` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '未达标原因',
  `answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '模型回答',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '评估链路追踪号',
  `result_chunks` json NULL DEFAULT NULL COMMENT '检索结果顺序(chunkId列表, 供指标计算)',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_task` (`task_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 评测逐题结果';
