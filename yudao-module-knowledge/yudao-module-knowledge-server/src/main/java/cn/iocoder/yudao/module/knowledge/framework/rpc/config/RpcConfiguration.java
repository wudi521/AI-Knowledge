package cn.iocoder.yudao.module.knowledge.framework.rpc.config;

import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "knowledgeRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {KnowledgeApi.class, IngestionApi.class})
public class RpcConfiguration {
}
