package cn.iocoder.yudao.module.workflow.api;

import cn.iocoder.yudao.module.workflow.api.WorkflowApi;
import org.springframework.stereotype.Service;

/**
 * 业务流程 对外 RPC 实现
 */
@Service
public class WorkflowApiImpl implements WorkflowApi {

    @Override
    public Boolean startFlow(String flowKey, Long bizId) {
        return false;
    }

}
