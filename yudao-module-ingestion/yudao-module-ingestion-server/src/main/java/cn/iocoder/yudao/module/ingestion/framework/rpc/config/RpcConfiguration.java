package cn.iocoder.yudao.module.ingestion.framework.rpc.config;

import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "ingestionRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {IngestionApi.class})
public class RpcConfiguration {
}
