package cn.iocoder.yudao.module.retrieval.framework.rpc.config;

import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.PromptApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "retrievalRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {ModelApi.class, PromptApi.class, KnowledgeApi.class, IngestionApi.class})
public class RpcConfiguration {
}
