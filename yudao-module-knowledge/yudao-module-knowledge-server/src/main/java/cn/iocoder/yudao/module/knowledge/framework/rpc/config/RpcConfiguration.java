package cn.iocoder.yudao.module.knowledge.framework.rpc.config;

import cn.iocoder.yudao.module.eval.api.EvalApi;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "knowledgeRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {KnowledgeApi.class, IngestionApi.class, ModelApi.class, PermissionApi.class, EvalApi.class})
public class RpcConfiguration {
}
