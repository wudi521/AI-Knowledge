-- 规则引擎 M7-A: AI 硬规则(Drools DRL) + 命中留痕(2026-08-21; 仅执行一次)
-- 说明: ai_rule 为租户级表(TenantBaseDO), 同 rule_key 多版本(1 启用 + 可选灰度), 与 ai_prompt 同模式

CREATE TABLE IF NOT EXISTS `ai_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `rule_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务键(如 warranty-condition/delivery-condition)',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '说明',
  `drl_content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'DRL 规则文本',
  `version` int NOT NULL DEFAULT 1 COMMENT '版本号(同 key 自增)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0停用/1启用全量/2灰度中(带 gray_tenant_ids)',
  `gray_tenant_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '灰度租户列表(JSON 数组)',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_key_status`(`rule_key` ASC, `status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 硬规则(Drools DRL)';

CREATE TABLE IF NOT EXISTS `ai_rule_hit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `rule_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务键',
  `rule_version` int NULL DEFAULT NULL COMMENT '命中规则版本',
  `query` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '问题',
  `facts` json NULL COMMENT '事实(JSON)',
  `conclusion` json NULL COMMENT '规则结论(JSON)',
  `llm_conclusion` json NULL COMMENT 'LLM 结论(预留冲突对比)',
  `deviated` tinyint NOT NULL DEFAULT 0 COMMENT '是否以规则为准偏离 LLM(预留)',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_time`(`tenant_id` ASC, `create_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 规则命中留痕';
