package cn.iocoder.yudao.module.model.service.secret.impl;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.model.service.secret.SecretCryptoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 密钥加密默认实现。
 * <p>
 * 主密钥只从环境变量/配置读取({@code YUDAO_SECRET_MASTER_KEY}, 32 字节 hex, 64 字符),
 * 不写入仓库/数据库; 每条密钥随机 nonce, 保存 ciphertext+nonce+keyVersion 供解密与轮换。
 */
@Slf4j
@Service
public class AesGcmSecretCryptoService implements SecretCryptoService {

    /** 密钥版本(轮换时递增, 旧版本密钥保留可解) */
    private static final int KEY_VERSION = 1;
    private static final int NONCE_LENGTH = 12;   // GCM 标准 nonce 12 字节
    private static final int TAG_LENGTH_BITS = 128;

    @Value("${yudao.model.secret.master-key:${YUDAO_SECRET_MASTER_KEY:}}")
    private String masterKeyHex;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public Encrypted encrypt(String plaintext) {
        if (StrUtil.isBlank(plaintext)) {
            return null;
        }
        byte[] key = resolveMasterKey();
        if (key == null) {
            log.error("[encrypt][未配置主密钥(YUDAO_SECRET_MASTER_KEY), 无法加密敏感字段]");
            return null;
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce), KEY_VERSION);
        } catch (Exception e) {
            log.error("[encrypt][加密失败: {}]", e.getMessage());
            return null;
        }
    }

    @Override
    public String decrypt(String ciphertext, String nonce, Integer keyVersion) {
        if (StrUtil.hasBlank(ciphertext, nonce) || keyVersion == null) {
            return null;
        }
        byte[] key = resolveMasterKey();
        if (key == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, Base64.getDecoder().decode(nonce)));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败(密钥轮换/数据损坏): 不抛, 由调用方降级
            log.error("[decrypt][解密失败: {}]", e.getMessage());
            return null;
        }
    }

    /** 解析主密钥: 环境变量 hex(64 字符 → 32 字节); 缺失返回 null */
    private byte[] resolveMasterKey() {
        if (StrUtil.isBlank(masterKeyHex)) {
            return null;
        }
        String hex = masterKeyHex.trim();
        if (hex.length() != 64) {
            log.error("[resolveMasterKey][主密钥必须为 32 字节 hex(64 字符), 当前长度 {}]", hex.length());
            return null;
        }
        try {
            byte[] key = new byte[32];
            for (int i = 0; i < 32; i++) {
                key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return key;
        } catch (NumberFormatException e) {
            log.error("[resolveMasterKey][主密钥非法 hex: {}]", e.getMessage());
            return null;
        }
    }
}
