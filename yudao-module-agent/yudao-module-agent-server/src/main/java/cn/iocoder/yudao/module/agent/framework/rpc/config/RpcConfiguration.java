package cn.iocoder.yudao.module.agent.framework.rpc.config;

import cn.iocoder.yudao.module.agent.api.AgentApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = {AgentApi.class})
public class RpcConfiguration {
}
