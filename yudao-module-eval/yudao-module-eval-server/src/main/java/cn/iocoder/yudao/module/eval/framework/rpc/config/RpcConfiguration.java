package cn.iocoder.yudao.module.eval.framework.rpc.config;

import cn.iocoder.yudao.module.eval.api.EvalApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "evalRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {EvalApi.class})
public class RpcConfiguration {
}
