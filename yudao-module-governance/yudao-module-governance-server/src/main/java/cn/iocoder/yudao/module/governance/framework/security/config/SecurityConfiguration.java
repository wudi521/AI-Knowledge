package cn.iocoder.yudao.module.governance.framework.security.config;

import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import cn.iocoder.yudao.framework.security.config.AuthorizeRequestsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * governance 模块的 Security 配置
 */
@Configuration(proxyBeanMethods = false, value = "governanceSecurityConfiguration")
public class SecurityConfiguration {

    @Bean("governanceAuthorizeRequestsCustomizer")
    public AuthorizeRequestsCustomizer authorizeRequestsCustomizer() {
        return new AuthorizeRequestsCustomizer() {

            @Override
            public void customize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
                // Swagger 接口文档
                registry.requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/webjars/**").permitAll()
                        .requestMatchers("/swagger-ui").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll();
                // Spring Boot Actuator 的安全配置
                // Actuator 仅开放最小存活/就绪探针, 其余端点需登录(内部认证)
                registry.requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/readiness").permitAll()
                        .requestMatchers("/actuator/liveness").permitAll();
                // Druid 监控: 移除匿名放行, 需登录后访问(生产安全)
                // 健康检查(骨架自定义)
                registry.requestMatchers("/governance/health/**").permitAll();
                // RPC 服务的安全配置
                registry.requestMatchers(ApiConstants.PREFIX + "/**").permitAll();
            }

        };
    }

}
