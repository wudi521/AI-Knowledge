package cn.iocoder.yudao.module.rule.framework.rpc.config;

import cn.iocoder.yudao.module.rule.api.RuleApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "ruleRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {RuleApi.class})
public class RpcConfiguration {
}
