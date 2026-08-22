# 企业级改造 · 02 模型密钥加密存储(批次 A2)

> 日期: 2026-08-22 · 对应实施规范 A2

## 1. 问题确认
- `ai_model_config.api_key` 明文存储; 响应虽脱敏, 但数据库/备份/日志可读明文。

## 2. 方案
- **AES-256-GCM**: `SecretCryptoService` 接口 + `AesGcmSecretCryptoService` 实现(预留 KMS/Vault 扩展位)
- 主密钥: 环境变量 `YUDAO_SECRET_MASTER_KEY`(32 字节 hex/64 字符), 不写入仓库
- 每条密钥随机 nonce(12B), 保存 ciphertext+nonce+keyVersion; 轮换兼容(版本隔离)
- **双读单写**: 新写只写密文(明文字段置空); 旧明文仅解密路径兼容(调用瞬间解密, 不缓存)
- 更新请求收到 `****` 等含 `*` 值 → 不修改
- 遗留明文迁移: 受保护管理接口 `POST /model/model-config/encrypt-legacy-api-keys`(幂等)

## 3. 文件
- `model/service/secret/SecretCryptoService.java`(新, 接口)
- `model/service/secret/impl/AesGcmSecretCryptoService.java`(新, AES-256-GCM)
- `AiModelConfigDO` + `AiModelConfigServiceImpl`(加密写/脱敏不改/遗留迁移) + `AiModelConfigService` + `AiModelConfigController`(迁移接口)
- `ModelInvoker.invoke` → `resolveApiKey`(调用瞬间解密, 密文优先, 明文兼容)
- `sql/migrate-20260822-model-api-key-encrypt.sql`(3 字段)
- model `application-local.yaml`: `yudao.model.secret.master-key`(环境变量, 默认空)

## 4. 验证
- 编译通过; 冒烟 6 项 ALL PASSED: 加解密往返/每次 nonce 不同/篡改密文返回 null/空白不加密/轮换隔离/无密钥不抛

## 5. 回滚
- 密文字段保留明文兼容(双读), 停用加密=环境变量不配 + 不调迁移接口; 代码回滚 git revert
