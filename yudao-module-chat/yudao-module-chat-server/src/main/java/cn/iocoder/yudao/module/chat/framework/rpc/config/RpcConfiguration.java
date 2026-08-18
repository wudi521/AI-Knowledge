package cn.iocoder.yudao.module.chat.framework.rpc.config;

import cn.iocoder.yudao.module.chat.api.ChatApi;
import cn.iocoder.yudao.module.evidence.api.EvidenceApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.model.api.ModelApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "chatRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {ChatApi.class, EvidenceApi.class, ModelApi.class, KnowledgeApi.class})
public class RpcConfiguration {
}
