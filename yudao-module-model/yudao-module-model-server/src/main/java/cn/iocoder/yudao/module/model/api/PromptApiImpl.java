package cn.iocoder.yudao.module.model.api;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.model.dal.dataobject.prompt.AiPromptDO;
import cn.iocoder.yudao.module.model.dal.mysql.prompt.AiPromptMapper;
import cn.iocoder.yudao.module.model.service.prompt.PromptCache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * Prompt 管理 RPC 实现
 */
@Slf4j
@RestController // Feign RPC 实现
@Validated
public class PromptApiImpl implements PromptApi {

    @Resource
    private AiPromptMapper aiPromptMapper;
    @Resource
    private PromptCache promptCache;

    @Override
    public CommonResult<String> getPrompt(String key, Long tenantId) {
        Long t = tenantId != null ? tenantId : TenantContextHolder.getTenantId();
        PromptCache.Entry entry = promptCache.get(key);
        if (entry == null) {
            List<AiPromptDO> rows = aiPromptMapper.selectByKeyAndStatusIn(key, List.of(1, 2));
            String enabled = null;
            String gray = null;
            List<Long> grayIds = List.of();
            for (AiPromptDO r : rows) {
                if (Integer.valueOf(1).equals(r.getStatus())) {
                    enabled = r.getContent();
                } else if (Integer.valueOf(2).equals(r.getStatus())) {
                    gray = r.getContent();
                    grayIds = parseTenantIds(r.getGrayTenantIds());
                }
            }
            entry = new PromptCache.Entry(enabled, gray, grayIds, System.currentTimeMillis() + 30_000L);
            promptCache.put(key, entry);
        }
        if (entry.grayContent() != null && t != null && entry.grayTenantIds().contains(t)) {
            return success(entry.grayContent());
        }
        return success(entry.enabledContent()); // null = 无启用配置
    }

    private List<Long> parseTenantIds(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return JSONUtil.toList(json, Long.class);
        } catch (Exception e) {
            return List.of();
        }
    }

}
