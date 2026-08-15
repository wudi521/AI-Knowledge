package cn.iocoder.yudao.module.governance.api;

import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
/**
 * 治理平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 governance-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface GovernanceApi {

    /** 占位方法: 按领域替换为真实接口 */
    java.math.BigDecimal monthCost(Long tenantId);

}
