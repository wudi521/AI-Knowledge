package cn.iocoder.yudao.module.eval.framework.eval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 评测平台业务配置类: 注册 {@link EvalProperties}(绑定 yudao.eval.*)
 * <p>
 * 对齐 evidence 模块 {@code EvidenceConfiguration} 模式。
 */
@Configuration(value = "evalEvalConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties(EvalProperties.class)
public class EvalConfiguration {
}
