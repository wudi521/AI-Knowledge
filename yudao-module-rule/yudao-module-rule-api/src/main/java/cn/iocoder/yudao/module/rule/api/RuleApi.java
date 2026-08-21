package cn.iocoder.yudao.module.rule.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.rule.api.dto.RuleEvaluateReqDTO;
import cn.iocoder.yudao.module.rule.api.dto.RuleEvaluateRespDTO;
import cn.iocoder.yudao.module.rule.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 规则引擎 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 rule-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface RuleApi {

    /**
     * 规则评估(命中硬规则 → 直接以规则结论作答)
     * <p>
     * 失败/未命中/未配置 → 调用方忽略规则走原链路(引擎内部已降级为不抛错, 此处仅兜底)
     *
     * @param req 评估请求(ruleKey + tenantId + facts)
     * @return matched=true 时 conclusions 为命中结论; 异常返回 error(调用方忽略)
     */
    @PostMapping(ApiConstants.PREFIX + "/evaluate")
    CommonResult<RuleEvaluateRespDTO> evaluate(@RequestBody RuleEvaluateReqDTO req);

}
