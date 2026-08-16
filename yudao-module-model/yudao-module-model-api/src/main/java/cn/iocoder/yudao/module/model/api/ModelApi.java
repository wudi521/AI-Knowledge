package cn.iocoder.yudao.module.model.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.model.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 模型网关 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 model-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface ModelApi {

    /** 占位方法: 按领域替换为真实接口 */
    String chat(String model, String prompt);

    /**
     * 文本向量化(Embedding)
     *
     * @param texts 文本列表(批量)
     * @return 每个文本对应的 1024 维向量列表
     */
    @PostMapping(ApiConstants.PREFIX + "/embedding")
    CommonResult<List<List<Float>>> embedding(@RequestBody List<String> texts);

}
