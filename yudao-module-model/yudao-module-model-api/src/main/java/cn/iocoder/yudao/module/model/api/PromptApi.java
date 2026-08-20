package cn.iocoder.yudao.module.model.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.model.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Prompt 管理 RPC(消费方取提示词; 无配置返回 null, 调用方回退内置默认)
 */
@FeignClient(name = ApiConstants.NAME)
public interface PromptApi {

    /**
     * 获取提示词内容(灰度命中 → 灰度版本, 否则启用版本; 无启用 → null)
     *
     * @param key      业务键
     * @param tenantId 租户(可为 null, 服务端 TenantContextHolder 兜底)
     */
    @GetMapping(ApiConstants.PREFIX + "/prompt/get-prompt")
    CommonResult<String> getPrompt(@RequestParam("key") String key,
                                   @RequestParam(value = "tenantId", required = false) Long tenantId);

}
