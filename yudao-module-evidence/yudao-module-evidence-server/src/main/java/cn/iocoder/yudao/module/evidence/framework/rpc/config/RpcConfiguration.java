package cn.iocoder.yudao.module.evidence.framework.rpc.config;

import cn.iocoder.yudao.module.evidence.api.EvidenceApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "evidenceRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {EvidenceApi.class})
public class RpcConfiguration {
}
