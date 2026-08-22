-- B1: ai_chunk 可追溯元数据 + 唯一约束/索引(批次 B, Flyway V2)
-- 说明: 由迁移执行方(yudao-server)启动时自动执行; baseline 已覆盖存量 schema, 本脚本为首次版本化迁移
-- 回滚: 按字段/索引逐项 DROP(见 docs/enterprise-upgrade/05-chunk-metadata.md)

ALTER TABLE `ai_chunk`
  ADD COLUMN `chunk_key` varchar(64) NULL DEFAULT NULL COMMENT '业务键(版本内稳定唯一)' AFTER `parent_id`,
  ADD COLUMN `chunk_seq` int NULL DEFAULT NULL COMMENT '版本内顺序' AFTER `chunk_key`,
  ADD COLUMN `chunk_role` varchar(16) NULL DEFAULT NULL COMMENT '角色: PARENT/CHILD/LEAF/TABLE/IMAGE' AFTER `chunk_seq`,
  ADD COLUMN `section_path` varchar(512) NULL DEFAULT NULL COMMENT '章节路径(标题链, > 分隔)' AFTER `chunk_role`,
  ADD COLUMN `source_page_start` int NULL DEFAULT -1 COMMENT '来源起始页(1-based; -1未知)' AFTER `section_path`,
  ADD COLUMN `source_page_end` int NULL DEFAULT -1 COMMENT '来源结束页(-1未知)' AFTER `source_page_start`,
  ADD COLUMN `token_count` int NULL DEFAULT NULL COMMENT 'token数(估算)' AFTER `source_page_end`,
  ADD COLUMN `content_hash` varchar(64) NULL DEFAULT NULL COMMENT '内容SHA-256' AFTER `token_count`;

-- 存量回填: chunk_key 唯一(legacy-{id} 前缀与新生成 c%06d 不冲突, 幂等)
UPDATE `ai_chunk` SET `chunk_key` = CONCAT('legacy-', `id`) WHERE `chunk_key` IS NULL OR `chunk_key` = '';

-- 唯一约束 (tenant_id, version_id, chunk_key): 版本内 chunk 业务键唯一, 支撑幂等 upsert
ALTER TABLE `ai_chunk` ADD UNIQUE KEY `uk_tenant_version_key` (`tenant_id`, `version_id`, `chunk_key`);

-- 查询/失效过滤索引
ALTER TABLE `ai_chunk` ADD INDEX `idx_tenant_version_status` (`tenant_id`, `version_id`, `status`);
-- 父子检索(子块命中回带父块上下文)
ALTER TABLE `ai_chunk` ADD INDEX `idx_tenant_parent` (`tenant_id`, `parent_id`);
