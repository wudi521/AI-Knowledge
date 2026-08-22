package cn.iocoder.yudao.module.retrieval.service.domain;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域查询策略注册表: 按 domainCode 索引, 未找到回退 GENERAL
 */
@Component
public class DomainQueryPolicyRegistry {

    private final Map<String, DomainQueryPolicy> policies = new LinkedHashMap<>();

    public DomainQueryPolicyRegistry(List<DomainQueryPolicy> policyList) {
        for (DomainQueryPolicy policy : policyList) {
            if (policy != null && StrUtil.isNotBlank(policy.domainCode())) {
                policies.put(policy.domainCode(), policy);
            }
        }
    }

    public DomainQueryPolicy get(String domainCode) {
        if (StrUtil.isBlank(domainCode)) {
            return policies.getOrDefault("GENERAL", policies.values().iterator().next());
        }
        return policies.getOrDefault(domainCode, policies.getOrDefault("GENERAL", policies.values().iterator().next()));
    }
}
