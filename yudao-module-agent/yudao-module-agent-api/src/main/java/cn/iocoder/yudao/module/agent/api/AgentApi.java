package cn.iocoder.yudao.module.agent.api;

import cn.iocoder.yudao.module.agent.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
/**
 * Agent编排 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 agent-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface AgentApi {

    /** 占位方法: 按领域替换为真实接口 */
    Boolean executeTool(String toolCode, String params);

}
