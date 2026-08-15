package cn.iocoder.yudao.module.workflow.framework.rpc.config;

import cn.iocoder.yudao.module.workflow.api.WorkflowApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "workflowRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {WorkflowApi.class})
public class RpcConfiguration {
}
