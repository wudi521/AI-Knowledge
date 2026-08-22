-- V9: 会话绑定知识库(专利 MVP Chat kbIds; 缺失才加)
-- 回滚: ALTER TABLE ai_conversation DROP COLUMN kb_ids;
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND COLUMN_NAME = 'kb_ids') = 0,
  'ALTER TABLE `ai_conversation` ADD COLUMN `kb_ids` varchar(512) NULL DEFAULT NULL COMMENT ''绑定知识库编号(逗号分隔; 专利MVP会话级)'' AFTER `intent`',
  'SELECT 1');
PREPARE s1 FROM @ddl; EXECUTE s1; DEALLOCATE PREPARE s1;
