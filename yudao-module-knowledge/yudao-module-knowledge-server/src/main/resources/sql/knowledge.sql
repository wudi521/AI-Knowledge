-- ----------------------------
-- AI 知识库
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_knowledge_base`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `chunk_strategy` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'ParentChild' COMMENT '切分策略: Semantic/ParentChild/Table/FAQ/Policy',
  `embed_model` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'BGE-M3' COMMENT 'Embedding 模型',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态: 0停用 1启用',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备注',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 知识库';

-- ----------------------------
-- AI 文档
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_document`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `kb_id` bigint NOT NULL COMMENT '知识库编号',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文档名',
  `type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '类型: PDF/WORD/EXCEL/PPT/IMAGE',
  `storage_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '存储路径(MinIO)',
  `file_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '文件 SHA-256',
  `parse_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '解析状态: PENDING/PARSING/EMBEDDING/INDEXED/FAILED',
  `error_msg` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '失败原因',
  `chunk_count` int NULL DEFAULT 0 COMMENT '切分片段数(解析结果)',
  `owner` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '上传人',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_kb`(`kb_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 文档';

-- ----------------------------
-- AI 文档版本
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_doc_version`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `doc_id` bigint NOT NULL COMMENT '文档编号',
  `version_no` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '版本号',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/REVIEW/PUBLISHED/EXPIRED/ARCHIVED',
  `effective_from` datetime NULL DEFAULT NULL COMMENT '生效开始时间',
  `effective_to` datetime NULL DEFAULT NULL COMMENT '生效结束时间',
  `reviewer` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '审核人',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_doc`(`doc_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 文档版本';

-- ----------------------------
-- AI 知识片段
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_chunk`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `version_id` bigint NOT NULL COMMENT '版本编号',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '片段内容',
  `chunk_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'POLICY' COMMENT '类型: SEMANTIC/TABLE/FAQ/POLICY',
  `metadata` json NULL DEFAULT NULL COMMENT '元数据',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PUBLISHED' COMMENT '状态',
  `vector_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'Milvus 向量关联键(默认=chunk_id)',
  `parent_id` bigint NULL DEFAULT NULL COMMENT '父块编号(ParentChild 子块用)',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_version`(`version_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 知识片段';

-- ----------------------------
-- 增量语句(已建库环境执行): ai_document 增加 error_msg/chunk_count, ai_chunk 增加 parent_id
-- 注意: 目标库为 MySQL 8.0, 不支持 ADD COLUMN IF NOT EXISTS; 本文件首次建库/迁移时执行一次,
--       列已存在时请跳过对应语句(或按 Task 5 迁移脚本顺序执行)
-- ----------------------------
ALTER TABLE `ai_document` ADD COLUMN `error_msg` varchar(512) NULL DEFAULT NULL COMMENT '失败原因' AFTER `parse_status`;
ALTER TABLE `ai_document` ADD COLUMN `chunk_count` int NULL DEFAULT 0 COMMENT '切分片段数(解析结果)' AFTER `error_msg`;
ALTER TABLE `ai_chunk` ADD COLUMN `parent_id` bigint NULL DEFAULT NULL COMMENT '父块编号(ParentChild 子块用)' AFTER `vector_key`;

-- ----------------------------
-- 知识治理增量(已建库环境执行, MySQL 8.0 兼容: 不用 IF NOT EXISTS)
-- ----------------------------
-- 1. 知识库: 可见角色(code 逗号分隔,空=全部可见) + 有效期至
ALTER TABLE `ai_knowledge_base` ADD COLUMN `visible_roles` varchar(512) NULL DEFAULT NULL COMMENT '可见角色code,逗号分隔;空=全部可见' AFTER `status`;
ALTER TABLE `ai_knowledge_base` ADD COLUMN `effective_to` datetime NULL DEFAULT NULL COMMENT '有效期至(空=永久)' AFTER `visible_roles`;

-- 2. 文档版本: 冲突状态 + 审核结果
ALTER TABLE `ai_doc_version` ADD COLUMN `conflict_status` tinyint NOT NULL DEFAULT 0 COMMENT '冲突状态:0无 1待裁决 2已裁决' AFTER `reviewer`;
ALTER TABLE `ai_doc_version` ADD COLUMN `review_result` varchar(16) NULL DEFAULT NULL COMMENT '审核结果: APPROVED/REJECTED' AFTER `conflict_status`;
ALTER TABLE `ai_doc_version` ADD COLUMN `review_comment` varchar(512) NULL DEFAULT NULL COMMENT '审核意见' AFTER `review_result`;

-- 3. 片段: 向量(JSON 数组字符串,管线阶段存 MySQL,发布时写 Milvus)
ALTER TABLE `ai_chunk` ADD COLUMN `embedding` text NULL DEFAULT NULL COMMENT 'BGE-M3向量(JSON数组,发布时写Milvus)' AFTER `content`;

-- 4. 审核条目
CREATE TABLE IF NOT EXISTS `ai_review_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `version_id` bigint NOT NULL COMMENT '版本编号',
  `doc_id` bigint NOT NULL COMMENT '文档编号',
  `chunk_id` bigint NULL DEFAULT NULL COMMENT '来源Chunk编号',
  `item_type` varchar(16) NOT NULL COMMENT '类型: POLICY/PRICE/LEGAL/FAQ/SOP',
  `title` varchar(255) NOT NULL COMMENT '条目主题',
  `content` varchar(2000) NOT NULL COMMENT '条目内容',
  `risk_level` varchar(8) NOT NULL DEFAULT 'MED' COMMENT '风险: HIGH/MED/LOW',
  `ai_confidence` decimal(4,3) NULL DEFAULT NULL COMMENT 'AI置信度',
  `must_review` tinyint NOT NULL DEFAULT 1 COMMENT '是否必审:1必审 0可自动',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED',
  `reviewer` varchar(64) NULL DEFAULT NULL COMMENT '审核人',
  `reviewer2` varchar(64) NULL DEFAULT NULL COMMENT '双人复核第二人(价格类)',
  `reject_reason` varchar(512) NULL DEFAULT NULL COMMENT '驳回原因',
  `review_time` datetime NULL DEFAULT NULL COMMENT '审核时间',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_version`(`version_id`) USING BTREE,
  INDEX `idx_doc`(`doc_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 审核条目';

-- 5. 版本冲突
CREATE TABLE IF NOT EXISTS `ai_conflict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `version_id` bigint NOT NULL COMMENT '新版本编号',
  `old_version_id` bigint NOT NULL COMMENT '旧已发布版本编号',
  `doc_id` bigint NOT NULL COMMENT '文档编号',
  `item_id` bigint NULL DEFAULT NULL COMMENT '关联审核条目',
  `title` varchar(255) NOT NULL COMMENT '冲突主题',
  `old_content` varchar(2000) NOT NULL COMMENT '旧版本表述',
  `new_content` varchar(2000) NOT NULL COMMENT '新版本表述',
  `rule_hit` tinyint NOT NULL DEFAULT 0 COMMENT '规则粗筛命中:1是',
  `llm_judgement` varchar(16) NULL DEFAULT NULL COMMENT 'LLM判定: CONFLICT/NO_CONFLICT',
  `llm_reason` varchar(1000) NULL DEFAULT NULL COMMENT 'LLM判定理由',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RESOLVED_NEW/RESOLVED_OLD',
  `resolver` varchar(64) NULL DEFAULT NULL COMMENT '裁决人',
  `resolve_time` datetime NULL DEFAULT NULL COMMENT '裁决时间',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_version`(`version_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 版本冲突';

