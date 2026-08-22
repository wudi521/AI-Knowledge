package cn.iocoder.yudao.framework.security.core.rpc;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * LoginUser 的 RequestInterceptor 实现类：Feign 请求时，将 {@link LoginUser} 设置到 header 中，继续透传给被调用的服务
 * <p>
 * 内部认证: 透传 login-user 时同时生成内部签名头(X-Internal-App/Timestamp/Signature),
 * 供被调服务校验该 login-user 头确实来自可信内部调用(防外部直连伪造)。
 *
 * @author 芋道源码
 */
@Slf4j
public class LoginUserRequestInterceptor implements RequestInterceptor {

    @Autowired(required = false)
    private InternalAuthService internalAuthService;

    @Override
    public void apply(RequestTemplate requestTemplate) {
        LoginUser user = SecurityFrameworkUtils.getLoginUser();
        if (user == null) {
            return;
        }
        try {
            String userStr = JsonUtils.toJsonString(user);
            userStr = URLEncoder.encode(userStr, StandardCharsets.UTF_8); // 编码，避免中文乱码
            requestTemplate.header(SecurityFrameworkUtils.LOGIN_USER_HEADER, userStr);
            // 内部认证(login-user 防伪造): 带 login-user 的 Feign 调用同时签名
            if (internalAuthService != null && internalAuthService.isReady()) {
                String timestamp = internalAuthService.currentTimestamp();
                String signature = internalAuthService.sign(internalAuthService.appId(),
                        requestTemplate.method(), requestTemplate.path(), timestamp, userStr);
                requestTemplate.header(InternalAuthService.HEADER_APP, internalAuthService.appId());
                requestTemplate.header(InternalAuthService.HEADER_TIMESTAMP, timestamp);
                requestTemplate.header(InternalAuthService.HEADER_SIGNATURE, signature);
            }
        } catch (Exception ex) {
            log.error("[apply][序列化 LoginUser({}) 发生异常]", user, ex);
            throw ex;
        }
    }

}
