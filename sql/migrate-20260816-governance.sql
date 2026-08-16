-- ============================================================
-- 知识治理存量迁移(2026-08-16)
-- 前置: 先执行 knowledge.sql 末尾"知识治理增量"段(MySQL 8.0 兼容版)
-- 目标库: ruoyi-vue-pro (MySQL 8.0)
-- ============================================================

-- 1. 存量 INDEXED 文档视为"已发布"(对应新管线 REVIEW -> 审核 -> PUBLISHED 的终态)
UPDATE `ai_document` SET `parse_status` = 'PUBLISHED' WHERE `parse_status` = 'INDEXED';

-- 2. 为存量文档补 PUBLISHED 版本记录(无版本记录的才补, version_no 从 V1 起)
INSERT INTO `ai_doc_version` (`doc_id`, `version_no`, `status`, `effective_from`, `creator`, `create_time`, `updater`, `update_time`, `deleted`, `tenant_id`)
SELECT d.`id`, 'V1', 'PUBLISHED', d.`create_time`, 'admin', NOW(), 'admin', NOW(), b'0', d.`tenant_id`
FROM `ai_document` d
WHERE d.`parse_status` = 'PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM `ai_doc_version` v WHERE v.`doc_id` = d.`id`);

-- 3. 存量 chunk.version_id 由文档 id 改为版本 id(旧数据 version_id == 文档 id)
UPDATE `ai_chunk` c
JOIN `ai_doc_version` v ON c.`version_id` = v.`doc_id` AND v.`status` = 'PUBLISHED'
SET c.`version_id` = v.`id`;
