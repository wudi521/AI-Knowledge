-- 模型 API Key 加密存储(A2; ALTER 仅执行一次)
-- 密文由应用层 AES-256-GCM 生成(需 YUDAO_SECRET_MASTER_KEY), 明文迁移通过管理接口
-- POST /model/model-config/encrypt-legacy-api-keys 执行(幂等)
ALTER TABLE `ai_model_config`
  ADD COLUMN `api_key_cipher` varchar(512) NULL DEFAULT NULL COMMENT 'API Key密文(base64, AES-256-GCM)' AFTER `api_key`,
  ADD COLUMN `api_key_nonce` varchar(64) NULL DEFAULT NULL COMMENT 'API Key加密nonce(base64)' AFTER `api_key_cipher`,
  ADD COLUMN `api_key_key_version` int NULL DEFAULT NULL COMMENT 'API Key密钥版本(轮换兼容)' AFTER `api_key_nonce`;
