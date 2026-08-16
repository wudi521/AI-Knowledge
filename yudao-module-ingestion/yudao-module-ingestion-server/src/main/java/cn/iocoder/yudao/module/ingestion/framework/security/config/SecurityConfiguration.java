package cn.iocoder.yudao.module.ingestion.framework.security.config;

import cn.iocoder.yudao.module.ingestion.enums.ApiConstants;
import cn.iocoder.yudao.framework.security.config.AuthorizeRequestsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * ingestion 模块的 Security 配置
 */
@Configuration(proxyBeanMethods = false, value = "ingestionSecurityConfiguration")
public class SecurityConfiguration {

    @Bean("ingestionAuthorizeRequestsCustomizer")
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
                registry.requestMatchers("/actuator").permitAll()
                        .requestMatchers("/actuator/**").permitAll();
                // Druid 监控
                registry.requestMatchers("/druid/**").permitAll();
                // 健康检查(骨架自定义)
                registry.requestMatchers("/ingestion/health/**").permitAll();
                // 管理后台 Chunk 接口需要登录鉴权(在下方 PREFIX permitAll 之前匹配)
                registry.requestMatchers(ApiConstants.PREFIX + "/chunk/**").authenticated();
                // RPC 服务的安全配置(其余 /admin-api/ingestion/** 保持 permitAll 供 Feign 调用)
                registry.requestMatchers(ApiConstants.PREFIX + "/**").permitAll();
            }

        };
    }

}
