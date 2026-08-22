package cn.iocoder.yudao.framework.security.core.rpc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 内部 RPC 身份认证配置(yudao.security.internal-auth.*)
 * <p>
 * 用途: 保护 login-user 请求头不被外部直连伪造——带 login-user 头的请求必须附有效内部签名
 * (X-Internal-App / X-Internal-Timestamp / X-Internal-Signature), 由 Feign 拦截器/网关自动生成。
 * <p>
 * 密钥约定: secretKey 从环境变量或 Nacos 加密配置读取(如 YUDAO_INTERNAL_AUTH_SECRET), 不写死仓库;
 * 生产环境必须显式配置, 未配置时 enabled 强制视为 false 并告警。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "yudao.security.internal-auth")
public class InternalAuthProperties {

    /** 是否启用内部认证(生产环境必须 true) */
    private boolean enabled = false;

    /** 本服务调用方标识(appId), 出站签名与入站校验用 */
    private String appId = "internal";

    /** HMAC-SHA256 共享密钥(环境变量覆盖; 为空且 enabled=true 时按未启用处理并告警) */
    private String secretKey = "";

    /** 时间戳窗口(秒), 超窗拒绝 */
    private int timestampWindowSeconds = 300;

}
