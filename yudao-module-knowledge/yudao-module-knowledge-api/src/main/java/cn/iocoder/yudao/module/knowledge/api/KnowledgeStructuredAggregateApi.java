package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateRespDTO;
import cn.iocoder.yudao.module.knowledge.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 独立的权威结构化聚合 RPC；与旧的行集 structured-query 解耦。 */
@FeignClient(name = ApiConstants.NAME, contextId = "knowledgeStructuredAggregateApi")
public interface KnowledgeStructuredAggregateApi {

    @PostMapping(ApiConstants.PREFIX + "/structured-aggregate")
    CommonResult<StructuredAggregateRespDTO> aggregate(@RequestBody StructuredAggregateReqDTO req);
}
