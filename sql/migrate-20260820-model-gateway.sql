-- 模型网关(2026-08-20; 仅执行一次)
ALTER TABLE `ai_model_config`
  ADD COLUMN `scenario` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '*' COMMENT '场景标识(如 A/B; *=默认场景)' AFTER `type`,
  ADD COLUMN `priority` int NOT NULL DEFAULT 0 COMMENT '降级顺序(同类型同场景内, 小者优先)' AFTER `scenario`;

CREATE TABLE IF NOT EXISTS `ai_model_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '链路追踪号(调用方透传)',
  `scenario` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '*' COMMENT '路由场景',
  `type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'chat/embedding/rerank',
  `model_id` bigint NULL DEFAULT NULL COMMENT '命中模型配置编号(yaml 兜底为 NULL)',
  `model_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '模型名快照',
  `provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '供应商快照',
  `attempt` int NOT NULL DEFAULT 1 COMMENT '第几次尝试',
  `prompt_chars` int NOT NULL DEFAULT 0 COMMENT '输入字符数',
  `completion_chars` int NOT NULL DEFAULT 0 COMMENT '输出字符数',
  `prompt_tokens` int NOT NULL DEFAULT 0 COMMENT '计量token(真实usage优先, 否则估算)',
  `completion_tokens` int NOT NULL DEFAULT 0 COMMENT '同上',
  `elapsed_ms` int NOT NULL DEFAULT 0 COMMENT '单次尝试耗时',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SUCCESS/FAILED/DEGRADED',
  `error_msg` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '失败原因(截断, 不含密钥)',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_type`(`tenant_id` ASC, `type` ASC, `create_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 模型调用计量';
