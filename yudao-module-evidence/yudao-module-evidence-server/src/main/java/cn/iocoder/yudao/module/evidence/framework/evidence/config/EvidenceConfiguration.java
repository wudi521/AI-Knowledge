package cn.iocoder.yudao.module.evidence.framework.evidence.config;

import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 证据平台业务配置类: 注册 {@link EvidenceProperties}
 */
@Configuration(value = "evidenceEvidenceConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties(EvidenceProperties.class)
public class EvidenceConfiguration {
}
