package cn.iocoder.yudao.module.ingestion.framework.rpc.config;

import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.model.api.ModelApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "ingestionRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {IngestionApi.class, KnowledgeApi.class, ModelApi.class})
public class RpcConfiguration {
}
