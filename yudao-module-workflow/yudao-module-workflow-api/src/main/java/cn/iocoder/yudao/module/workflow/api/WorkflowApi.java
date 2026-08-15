package cn.iocoder.yudao.module.workflow.api;

import cn.iocoder.yudao.module.workflow.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
/**
 * 业务流程 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 workflow-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface WorkflowApi {

    /** 占位方法: 按领域替换为真实接口 */
    Boolean startFlow(String flowKey, Long bizId);

}
