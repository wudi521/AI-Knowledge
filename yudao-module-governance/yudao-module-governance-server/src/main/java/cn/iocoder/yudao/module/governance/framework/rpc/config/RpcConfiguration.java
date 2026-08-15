package cn.iocoder.yudao.module.governance.framework.rpc.config;

import cn.iocoder.yudao.module.governance.api.GovernanceApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = {GovernanceApi.class})
public class RpcConfiguration {
}
