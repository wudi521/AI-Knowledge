package cn.iocoder.yudao.module.governance.api;

import cn.iocoder.yudao.module.governance.api.GovernanceApi;
import org.springframework.stereotype.Service;

/**
 * 治理平台 对外 RPC 实现
 */
@Service
public class GovernanceApiImpl implements GovernanceApi {

    @Override
    public java.math.BigDecimal monthCost(Long tenantId) {
        return java.math.BigDecimal.ZERO;
    }

}
