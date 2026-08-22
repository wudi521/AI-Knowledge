package cn.iocoder.yudao.framework.security.core.rpc;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部 RPC 身份认证服务: HMAC-SHA256 签名生成与校验。
 * <p>
 * 签名内容 = appId + method + path + timestamp + loginUserValue(可空), 覆盖重放窗口由 timestamp 控制。
 * 设计目标: 带 login-user 头的请求必须来自可信内部调用(Feign/网关), 外部直连无法伪造用户身份。
 */
@Slf4j
@Component
public class InternalAuthService {

    /** 请求头常量 */
    public static final String HEADER_APP = "X-Internal-App";
    public static final String HEADER_TIMESTAMP = "X-Internal-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Internal-Signature";

    @Resource
    private InternalAuthProperties properties;

    /**
     * 生成签名(Feign 拦截器/网关出站用)
     *
     * @param loginUserValue login-user 头原始值(可空: 无用户上下文的调用可不签名)
     */
    public String sign(String appId, String method, String path, String timestamp, String loginUserValue) {
        if (!isReady()) {
            return "";
        }
        String content = appId + "\n" + method + "\n" + path + "\n" + timestamp + "\n"
                + (loginUserValue == null ? "" : loginUserValue);
        HMac hmac = new HMac(HmacAlgorithm.HmacSHA256, properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
        return hmac.digestHex(content);
    }

    /**
     * 校验带 login-user 头的请求(入站): 头齐全 + 时间窗口内 + 签名匹配。
     * 未启用认证时恒为 true(兼容现状); 启用但缺密钥时拒绝并告警。
     */
    public boolean verify(String appId, String method, String path, String timestamp,
                          String loginUserValue, String signature) {
        if (!properties.isEnabled()) {
            return true; // 未启用: 兼容现状(开发环境)
        }
        if (!isReady()) {
            return false;
        }
        if (StrUtil.hasBlank(appId, timestamp, signature)) {
            return false;
        }
        // 时间窗口校验
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > properties.getTimestampWindowSeconds()) {
            return false;
        }
        // 签名比对(常量时间比较)
        String expected = sign(appId, method, path, timestamp, loginUserValue);
        if (StrUtil.isBlank(expected)) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /** 当前秒级时间戳(出站用) */
    public String currentTimestamp() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    /** 本服务调用方标识(出站签名用) */
    public String appId() {
        return properties.getAppId();
    }

    /** 认证是否可执行: 启用且有密钥; 启用但缺密钥时告警并按未启用处理 */
    public boolean isReady() {
        if (!properties.isEnabled()) {
            return false;
        }
        if (StrUtil.isBlank(properties.getSecretKey())) {
            log.error("[isReady][内部认证已启用但 secretKey 为空(请配置环境变量 YUDAO_INTERNAL_AUTH_SECRET), 按未启用处理]");
            return false;
        }
        return true;
    }
}
