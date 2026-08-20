-- ============================================================
-- 知识库槽位定义(2026-08-18)
-- 目标库: ruoyi-vue-pro (MySQL 8.0); 需先存在 ai_evidence_eval 表(证据平台已交付)
-- ============================================================

CREATE TABLE IF NOT EXISTS `ai_knowledge_base_slot` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `kb_id` bigint NOT NULL COMMENT '知识库编号(逻辑关联 ai_knowledge_base.id)',
  `slot_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '槽位编码(如 brand/faultType/purchaseTime)',
  `slot_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '槽位名(如 品牌型号)',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '抽取说明(喂给 LLM 的定义)',
  `required` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否必填(1=缺则反问)',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序(组反问句顺序)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态: 0=启用 1=禁用(CommonStatusEnum; 注意 ai_knowledge_base.status 语义相反)',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_kb_slot`(`kb_id`, `slot_code`, `deleted`) USING BTREE,
  INDEX `idx_kb`(`kb_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 知识库槽位定义';

-- ai_evidence_eval 增量 3 列(槽位检测结果落库; 仅执行一次, 重复执行会报 Duplicate column)
ALTER TABLE `ai_evidence_eval`
  ADD COLUMN `slots` json NULL COMMENT '抽取的槽位值(JSON)',
  ADD COLUMN `missing_slots` json NULL COMMENT '缺失必填槽位(JSON)',
  ADD COLUMN `clarify_question` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '反问句(缺槽位时)';

-- 增量: 槽位自动生成(2026-08-19; 仅执行一次, 重复执行会报 Duplicate column)
ALTER TABLE `ai_knowledge_base_slot`
  ADD COLUMN `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MANUAL' COMMENT '来源: LLM_AUTO(总结器生成, 可覆盖)/ MANUAL(用户创建或编辑过, 受保护)';
ALTER TABLE `ai_knowledge_base_slot`
  ADD INDEX `idx_kb_source`(`kb_id` ASC, `source` ASC) USING BTREE;
