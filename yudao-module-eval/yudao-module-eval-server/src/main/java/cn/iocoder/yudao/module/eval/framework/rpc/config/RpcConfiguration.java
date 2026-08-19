package cn.iocoder.yudao.module.eval.framework.rpc.config;

import cn.iocoder.yudao.module.eval.api.EvalApi;
import cn.iocoder.yudao.module.evidence.api.EvidenceApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "evalRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {EvalApi.class, EvidenceApi.class, KnowledgeApi.class})
public class RpcConfiguration {
}
