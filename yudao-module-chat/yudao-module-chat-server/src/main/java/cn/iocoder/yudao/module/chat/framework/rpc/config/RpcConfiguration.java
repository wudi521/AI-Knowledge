package cn.iocoder.yudao.module.chat.framework.rpc.config;

import cn.iocoder.yudao.module.chat.api.ChatApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = {ChatApi.class})
public class RpcConfiguration {
}
