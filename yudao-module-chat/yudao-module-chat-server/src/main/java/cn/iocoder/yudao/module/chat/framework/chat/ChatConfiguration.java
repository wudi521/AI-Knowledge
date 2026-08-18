package cn.iocoder.yudao.module.chat.framework.chat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对话工作台业务配置类: 注册 {@link ChatProperties} / {@link TransferProperties}
 * <p>
 * 镜像 evidence-server 的 {@code EvidenceConfiguration} 模式(proxyBeanMethods=false + EnableConfigurationProperties),
 * 由 {@code @SpringBootApplication} 组件扫描自动加载。
 */
@Configuration(value = "chatChatConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties({ChatProperties.class, TransferProperties.class})
public class ChatConfiguration {
}
