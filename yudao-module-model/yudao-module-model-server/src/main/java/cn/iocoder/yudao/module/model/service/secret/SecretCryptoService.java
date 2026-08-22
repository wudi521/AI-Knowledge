package cn.iocoder.yudao.module.model.service.secret;

/**
 * 密钥加密服务抽象: 模型 API Key 等敏感字段的加密/解密。
 * 默认实现 AES-256-GCM; 可扩展 KMS/Vault 实现(实现本接口并替换 Bean 即可)。
 */
public interface SecretCryptoService {

    /** 加密结果: 密文 + nonce + 密钥版本(均需持久化, 解密时用) */
    record Encrypted(String ciphertext, String nonce, Integer keyVersion) {
    }

    /**
     * 加密明文
     *
     * @param plaintext 明文(空/空白返回 null, 不产生密文)
     * @return 密文结果; plaintext 空白时返回 null
     */
    Encrypted encrypt(String plaintext);

    /**
     * 解密
     *
     * @param ciphertext 密文(base64)
     * @param nonce      随机数(base64)
     * @param keyVersion 密钥版本(轮换兼容)
     * @return 明文; 参数不齐或解密失败返回 null(调用方降级, 不抛)
     */
    String decrypt(String ciphertext, String nonce, Integer keyVersion);
}
