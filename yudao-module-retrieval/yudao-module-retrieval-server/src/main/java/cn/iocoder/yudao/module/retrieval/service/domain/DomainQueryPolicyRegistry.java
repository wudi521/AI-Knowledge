package cn.iocoder.yudao.module.retrieval.service.domain;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @deprecated 旧 QueryAnalysis 兼容门面。领域策略选择已统一使用 DomainPluginResolver。
 */
@Deprecated
@Component
public class DomainQueryPolicyRegistry {

    private final DomainPluginResolver<DomainQueryPolicy> resolver;

    public DomainQueryPolicyRegistry(List<DomainQueryPolicy> policyList) {
        this.resolver = new DomainPluginResolver<>(policyList);
    }

    public DomainQueryPolicy get(String domainCode) {
        return resolver.requireFirst(DomainPluginContext.of(domainCode), "retrieval query policy");
    }
}
