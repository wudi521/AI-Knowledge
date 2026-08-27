package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderRespDTO;
import cn.iocoder.yudao.module.knowledge.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 权威结构化排序 RPC。
 *
 * <p>请求只接受 fieldCode/transformCode/direction 等受控执行符号，不接受列名、SQL 或任意表达式。
 * 服务端白名单只为能够证明与 Query Engine typed semantics 等价的组合开放。</p>
 */
@FeignClient(name = ApiConstants.NAME, contextId = "knowledgeStructuredOrderApi")
public interface KnowledgeStructuredOrderApi {

    @PostMapping(ApiConstants.PREFIX + "/structured-order")
    CommonResult<StructuredOrderRespDTO> order(@RequestBody StructuredOrderReqDTO req);
}
