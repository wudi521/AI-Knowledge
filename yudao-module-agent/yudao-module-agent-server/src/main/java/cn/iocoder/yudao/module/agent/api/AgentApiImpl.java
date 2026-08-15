package cn.iocoder.yudao.module.agent.api;

import cn.iocoder.yudao.module.agent.api.AgentApi;
import org.springframework.stereotype.Service;

/**
 * Agent编排 对外 RPC 实现
 */
@Service
public class AgentApiImpl implements AgentApi {

    @Override
    public Boolean executeTool(String toolCode, String params) {
        return false;
    }

}
