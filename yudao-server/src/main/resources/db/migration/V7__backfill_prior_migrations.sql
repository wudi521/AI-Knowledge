-- V7: 回补历史手动迁移(A2 密钥加密 / 模型单价 / 文档级切分策略), 幂等——列存在则跳过
-- 背景: 上述变更原在 sql/migrate-*.sql 手动执行, 未入 Flyway; 本迁移保证全新环境部署时列结构完整,
--       已手动执行过的环境(列已存在)自动跳过, 不报重复列错误。
-- 回滚: 见各段注释(反向 DROP)

-- ① ai_model_config: A2 API Key 加密字段(缺失才加)
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND COLUMN_NAME = 'api_key_cipher') = 0,
  'ALTER TABLE `ai_model_config` ADD COLUMN `api_key_cipher` varchar(512) NULL DEFAULT NULL COMMENT ''API Key密文(base64, AES-256-GCM)'' AFTER `api_key`',
  'SELECT 1');
PREPARE s1 FROM @ddl; EXECUTE s1; DEALLOCATE PREPARE s1;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND COLUMN_NAME = 'api_key_nonce') = 0,
  'ALTER TABLE `ai_model_config` ADD COLUMN `api_key_nonce` varchar(64) NULL DEFAULT NULL COMMENT ''API Key加密nonce(base64)'' AFTER `api_key_cipher`',
  'SELECT 1');
PREPARE s2 FROM @ddl; EXECUTE s2; DEALLOCATE PREPARE s2;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND COLUMN_NAME = 'api_key_key_version') = 0,
  'ALTER TABLE `ai_model_config` ADD COLUMN `api_key_key_version` int NULL DEFAULT NULL COMMENT ''API Key密钥版本(轮换兼容)'' AFTER `api_key_nonce`',
  'SELECT 1');
PREPARE s3 FROM @ddl; EXECUTE s3; DEALLOCATE PREPARE s3;

-- ② ai_model_config: 成本单价列(每百万 token, 元; 缺失才加)
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND COLUMN_NAME = 'in_per_mtok') = 0,
  'ALTER TABLE `ai_model_config` ADD COLUMN `in_per_mtok` decimal(10,4) NULL DEFAULT NULL COMMENT ''输入单价(每百万token, 元)'' AFTER `dimensions`',
  'SELECT 1');
PREPARE s4 FROM @ddl; EXECUTE s4; DEALLOCATE PREPARE s4;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND COLUMN_NAME = 'out_per_mtok') = 0,
  'ALTER TABLE `ai_model_config` ADD COLUMN `out_per_mtok` decimal(10,4) NULL DEFAULT NULL COMMENT ''输出单价(每百万token, 元)'' AFTER `in_per_mtok`',
  'SELECT 1');
PREPARE s5 FROM @ddl; EXECUTE s5; DEALLOCATE PREPARE s5;

-- ③ ai_document: 文档级切分策略列(缺失才加; 存量回填)
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_document' AND COLUMN_NAME = 'chunk_strategy') = 0,
  'ALTER TABLE `ai_document` ADD COLUMN `chunk_strategy` varchar(32) NOT NULL DEFAULT ''auto'' COMMENT ''切分策略'' AFTER `file_hash`',
  'SELECT 1');
PREPARE s6 FROM @ddl; EXECUTE s6; DEALLOCATE PREPARE s6;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_document' AND COLUMN_NAME = 'chunk_strategy_params') = 0,
  'ALTER TABLE `ai_document` ADD COLUMN `chunk_strategy_params` varchar(1024) NULL DEFAULT NULL COMMENT ''切分策略参数(JSON)'' AFTER `chunk_strategy`',
  'SELECT 1');
PREPARE s7 FROM @ddl; EXECUTE s7; DEALLOCATE PREPARE s7;

UPDATE `ai_document` SET `chunk_strategy` = 'auto' WHERE `chunk_strategy` IS NULL OR `chunk_strategy` = '';

-- ④ ai_knowledge_base: 移除知识库级切分策略/Embedding 模型列(存在才删, 幂等)
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_base' AND COLUMN_NAME = 'chunk_strategy') > 0,
  'ALTER TABLE `ai_knowledge_base` DROP COLUMN `chunk_strategy`',
  'SELECT 1');
PREPARE s8 FROM @ddl; EXECUTE s8; DEALLOCATE PREPARE s8;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_base' AND COLUMN_NAME = 'embed_model') > 0,
  'ALTER TABLE `ai_knowledge_base` DROP COLUMN `embed_model`',
  'SELECT 1');
PREPARE s9 FROM @ddl; EXECUTE s9; DEALLOCATE PREPARE s9;
