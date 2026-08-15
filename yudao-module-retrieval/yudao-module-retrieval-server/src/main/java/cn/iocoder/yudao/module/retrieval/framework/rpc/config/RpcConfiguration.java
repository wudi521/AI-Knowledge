package cn.iocoder.yudao.module.retrieval.framework.rpc.config;

import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "retrievalRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {RetrievalApi.class})
public class RpcConfiguration {
}
