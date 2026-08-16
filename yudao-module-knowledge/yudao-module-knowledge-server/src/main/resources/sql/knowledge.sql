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
-- 注意: MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS, 重复执行会报错, 已存在则跳过
-- ----------------------------
ALTER TABLE `ai_document` ADD COLUMN IF NOT EXISTS `error_msg` varchar(512) NULL DEFAULT NULL COMMENT '失败原因' AFTER `parse_status`;
ALTER TABLE `ai_document` ADD COLUMN IF NOT EXISTS `chunk_count` int NULL DEFAULT 0 COMMENT '切分片段数(解析结果)' AFTER `error_msg`;
ALTER TABLE `ai_chunk` ADD COLUMN IF NOT EXISTS `parent_id` bigint NULL DEFAULT NULL COMMENT '父块编号(ParentChild 子块用)' AFTER `vector_key`;

