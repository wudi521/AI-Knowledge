package cn.iocoder.yudao.module.retrieval.service.prompt;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.model.api.PromptApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Prompt 管理消费支持: 取 DB 提示词(灰度/版本化), 失败或未配置回退内置默认
 */
@Slf4j
@Component
public class PromptSupport {

    @Resource
    private PromptApi promptApi;

    /**
     * 获取提示词内容
     *
     * @param key           业务键(如 slot-detect / query-analysis)
     * @param defaultPrompt 内置默认(DB 无配置/调用失败时回退)
     */
    public String get(String key, String defaultPrompt) {
        try {
            LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
            Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
            String p = promptApi.getPrompt(key, tenantId).getCheckedData();
            return StrUtil.isBlank(p) ? defaultPrompt : p;
        } catch (Exception e) {
            log.warn("[get][prompt key({}) 获取失败, 回退内置默认: {}]", key, e.getMessage());
            return defaultPrompt;
        }
    }
}
