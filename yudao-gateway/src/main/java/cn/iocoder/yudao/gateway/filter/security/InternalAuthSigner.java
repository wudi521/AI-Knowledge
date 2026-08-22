package cn.iocoder.yudao.gateway.filter.security;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 网关内部认证签名器: 网关在设置 login-user 头时同步生成内部签名头,
 * 供业务服务校验该 login-user 头确实来自可信来源(网关), 防外部直连伪造。
 * 签名算法与业务侧 InternalAuthService 保持一致(HMAC-SHA256, 共享密钥)。
 */
@Component
public class InternalAuthSigner {

    public static final String HEADER_APP = "X-Internal-App";
    public static final String HEADER_TIMESTAMP = "X-Internal-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Internal-Signature";

    @Value("${yudao.gateway.internal-auth.secret-key:${YUDAO_INTERNAL_AUTH_SECRET:dev-internal-secret-2026}}")
    private String secretKey;

    @Value("${yudao.gateway.internal-auth.app-id:gateway}")
    private String appId;

    /**
     * 为已设置的 login-user 头补充内部签名头
     *
     * @param builder        请求构建器(login-user 头已设置)
     * @param method         实际请求方法(如 GET/POST)
     * @param path           实际请求路径(如 /admin-api/knowledge/get-document)
     * @param loginUserValue login-user 头原始值(已 URL 编码)
     */
    public void sign(ServerHttpRequest.Builder builder, String method, String path, String loginUserValue) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String content = appId + "\n" + method + "\n" + path + "\n" + timestamp + "\n"
                + (loginUserValue == null ? "" : loginUserValue);
        HMac hmac = new HMac(HmacAlgorithm.HmacSHA256, secretKey.getBytes(StandardCharsets.UTF_8));
        String signature = hmac.digestHex(content);
        builder.header(HEADER_APP, appId)
                .header(HEADER_TIMESTAMP, timestamp)
                .header(HEADER_SIGNATURE, signature);
    }
}
