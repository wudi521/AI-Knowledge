-- V14: 固化会话的单知识库上下文(兼容旧 kb_ids)
-- 旧 kb_ids 仅用于迁移期回填和读取兼容；本迁移不从 creator 推断 user_id。

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND COLUMN_NAME = 'kb_id') = 0,
  'ALTER TABLE `ai_conversation` ADD COLUMN `kb_id` bigint NULL DEFAULT NULL COMMENT ''固定绑定的知识库编号'' AFTER `intent`',
  'SELECT 1');
PREPARE s1 FROM @ddl; EXECUTE s1; DEALLOCATE PREPARE s1;

-- 先以 NULL 增加，完成旧数据领域回填后再设置默认 GENERAL，避免已有行被默认值遮蔽。
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND COLUMN_NAME = 'domain_code') = 0,
  'ALTER TABLE `ai_conversation` ADD COLUMN `domain_code` varchar(32) NULL DEFAULT NULL COMMENT ''创建时快照的知识领域'' AFTER `kb_id`',
  'SELECT 1');
PREPARE s2 FROM @ddl; EXECUTE s2; DEALLOCATE PREPARE s2;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND COLUMN_NAME = 'user_id') = 0,
  'ALTER TABLE `ai_conversation` ADD COLUMN `user_id` bigint NULL DEFAULT NULL COMMENT ''创建会话的用户编号'' AFTER `domain_code`',
  'SELECT 1');
PREPARE s3 FROM @ddl; EXECUTE s3; DEALLOCATE PREPARE s3;

-- 只填充尚未设置 kb_id 的记录，取旧 kb_ids 中第一个数字 token。
UPDATE `ai_conversation`
SET `kb_id` = CAST(TRIM(SUBSTRING_INDEX(CONCAT(`kb_ids`, ','), ',', 1)) AS UNSIGNED)
WHERE `kb_id` IS NULL
  AND `kb_ids` IS NOT NULL
  AND TRIM(SUBSTRING_INDEX(CONCAT(`kb_ids`, ','), ',', 1)) REGEXP '^[1-9][0-9]{0,18}$'
  AND (CHAR_LENGTH(TRIM(SUBSTRING_INDEX(CONCAT(`kb_ids`, ','), ',', 1))) < 19
       OR CAST(TRIM(SUBSTRING_INDEX(CONCAT(`kb_ids`, ','), ',', 1)) AS UNSIGNED) <= 9223372036854775807);

-- 领域从同租户知识库快照；知识库缺失或领域为空时使用 GENERAL。
UPDATE `ai_conversation` c
LEFT JOIN `ai_knowledge_base` kb
  ON kb.`id` = c.`kb_id` AND kb.`tenant_id` = c.`tenant_id`
SET c.`domain_code` = COALESCE(NULLIF(kb.`domain_code`, ''), 'GENERAL')
WHERE c.`domain_code` IS NULL OR TRIM(c.`domain_code`) = '';

-- 最终列默认 GENERAL；重复执行时仅在默认值不正确时修改。
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation'
      AND COLUMN_NAME = 'domain_code' AND COALESCE(COLUMN_DEFAULT, '') <> 'GENERAL') > 0,
  'ALTER TABLE `ai_conversation` MODIFY COLUMN `domain_code` varchar(32) NULL DEFAULT ''GENERAL'' COMMENT ''创建时快照的知识领域''',
  'SELECT 1');
PREPARE s4 FROM @ddl; EXECUTE s4; DEALLOCATE PREPARE s4;

-- 租户、用户、知识库联合辅助索引，按索引名检测以保证重复执行安全。
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation'
      AND INDEX_NAME = 'idx_tenant_user_kb') = 0,
  'ALTER TABLE `ai_conversation` ADD INDEX `idx_tenant_user_kb` (`tenant_id`, `user_id`, `kb_id`)',
  'SELECT 1');
PREPARE s5 FROM @ddl; EXECUTE s5; DEALLOCATE PREPARE s5;
