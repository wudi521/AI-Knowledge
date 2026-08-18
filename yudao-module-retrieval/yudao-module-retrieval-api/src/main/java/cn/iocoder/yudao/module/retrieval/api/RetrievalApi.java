package cn.iocoder.yudao.module.retrieval.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
/**
 * 检索平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 retrieval-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface RetrievalApi {

    /**
     * 混合检索(证据等模块调用: 召回/重排/权限过滤/总结回答, 租户与用户显式传递)
     */
    @PostMapping(ApiConstants.PREFIX + "/search-rpc")
    CommonResult<RetrievalSearchRespDTO> search(@RequestBody RetrievalSearchReqDTO req);

}
