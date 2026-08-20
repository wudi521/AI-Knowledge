package cn.iocoder.yudao.module.model.framework.rpc.config;

import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.PromptApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "modelRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {ModelApi.class, PromptApi.class})
public class RpcConfiguration {
}
