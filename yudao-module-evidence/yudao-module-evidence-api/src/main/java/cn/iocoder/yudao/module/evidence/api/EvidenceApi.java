package cn.iocoder.yudao.module.evidence.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateReqDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 证据平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 evidence-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface EvidenceApi {

    /**
     * 证据评估(对话等模块调用: 检索组装→去重→冲突→充分性→生成+Claim验证, 租户与用户显式传递)
     */
    @PostMapping(ApiConstants.PREFIX + "/evaluate-rpc")
    CommonResult<EvidenceEvaluateRespDTO> evaluate(@RequestBody EvidenceEvaluateReqDTO req);

}
