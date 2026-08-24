-- V20: 多轮结果集引用重校验支持(CQ-38/阶段3)
-- ai_chat_result_set 增加 kb_id/domain_code: 供引用时校验 tenant/kb/domain 一致 + 文档 ACL/版本可见性。
-- 幂等: 已存在列则跳过(与 V19 同风格)。

SET @ddl1 := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_chat_result_set' AND COLUMN_NAME = 'kb_id') = 0,
  'ALTER TABLE `ai_chat_result_set` ADD COLUMN `kb_id` bigint NULL DEFAULT NULL COMMENT ''知识库编号(产生该结果集的知识库)'' AFTER `conversation_id`',
  'SELECT 1');
PREPARE s1 FROM @ddl1; EXECUTE s1; DEALLOCATE PREPARE s1;

SET @ddl2 := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_chat_result_set' AND COLUMN_NAME = 'domain_code') = 0,
  'ALTER TABLE `ai_chat_result_set` ADD COLUMN `domain_code` varchar(32) NULL DEFAULT NULL COMMENT ''知识领域编码(PATENT/PRODUCT...)'' AFTER `kb_id`',
  'SELECT 1');
PREPARE s2 FROM @ddl2; EXECUTE s2; DEALLOCATE PREPARE s2;
