-- V8: 专利领域 MVP v0.1 — 知识库领域标识 + 文档领域元数据(批次 A)
-- 回滚: ALTER TABLE ai_knowledge_base DROP COLUMN domain_code; ALTER TABLE ai_document DROP COLUMN domain_metadata;

-- 知识库领域标识(默认 GENERAL 不破坏历史数据)
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_base' AND COLUMN_NAME = 'domain_code') = 0,
  'ALTER TABLE `ai_knowledge_base` ADD COLUMN `domain_code` varchar(32) NOT NULL DEFAULT ''GENERAL'' COMMENT ''知识领域: GENERAL/PATENT'' AFTER `name`',
  'SELECT 1');
PREPARE s1 FROM @ddl; EXECUTE s1; DEALLOCATE PREPARE s1;

-- 文档领域元数据(JSON, 专利著录信息等; 缺失才加)
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_document' AND COLUMN_NAME = 'domain_metadata') = 0,
  'ALTER TABLE `ai_document` ADD COLUMN `domain_metadata` longtext NULL COMMENT ''领域文档元数据(JSON: 专利著录信息等)'' AFTER `chunk_strategy_params`',
  'SELECT 1');
PREPARE s2 FROM @ddl; EXECUTE s2; DEALLOCATE PREPARE s2;
