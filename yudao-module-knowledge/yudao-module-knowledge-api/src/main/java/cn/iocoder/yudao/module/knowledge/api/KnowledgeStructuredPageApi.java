package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 结构化源数据 keyset 分页 RPC。
 *
 * <p>这是物理数据访问合同，不承载用户问题语义。Query Engine 在无法整计划下推时可以分页读取完整源，
 * 避免把固定 rowCap 当成整个知识库的正确性边界。</p>
 */
@FeignClient(name = "yudao-module-knowledge-server", contextId = "knowledgeStructuredPageApi")
public interface KnowledgeStructuredPageApi {

    @PostMapping("/rpc-api/knowledge/structured/page")
    CommonResult<StructuredQueryRespDTO> page(@RequestBody StructuredQueryReqDTO request);
}
