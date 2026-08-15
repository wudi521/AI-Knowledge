package cn.iocoder.yudao.module.model.framework.rpc.config;

import cn.iocoder.yudao.module.model.api.ModelApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = {ModelApi.class})
public class RpcConfiguration {
}
