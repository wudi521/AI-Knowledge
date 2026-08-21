package cn.iocoder.yudao.module.evidence.framework.rpc.config;

import cn.iocoder.yudao.module.evidence.api.EvidenceApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.PromptApi;
import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import cn.iocoder.yudao.module.rule.api.RuleApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "evidenceRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {EvidenceApi.class, RetrievalApi.class, ModelApi.class, PromptApi.class, KnowledgeApi.class, RuleApi.class})
public class RpcConfiguration {
}
