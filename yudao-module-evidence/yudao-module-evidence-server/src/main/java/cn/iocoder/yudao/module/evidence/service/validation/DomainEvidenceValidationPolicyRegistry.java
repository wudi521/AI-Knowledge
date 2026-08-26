package cn.iocoder.yudao.module.evidence.service.validation;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.domain.EvidenceDomainResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 证据验证行业插件统一解析器。
 *
 * <p>复用平台 DomainPluginResolver，不维护验证专属 domain->policy Map。</p>
 */
@Component
public class DomainEvidenceValidationPolicyRegistry {

    private final DomainPluginResolver<DomainEvidenceValidationPolicy> resolver;

    public DomainEvidenceValidationPolicyRegistry(List<DomainEvidenceValidationPolicy> policies) {
        this.resolver = new DomainPluginResolver<>(policies);
    }

    public List<DomainEvidenceValidationPolicy> resolve(List<Evidence> evidences) {
        String domainCode = EvidenceDomainResolver.resolve(evidences);
        return resolver.resolve(new DomainPluginContext(null, null, domainCode,
                Set.of("EVIDENCE_VALIDATION"), Map.of()));
    }

    public boolean skipGenericConflictDetection(List<Evidence> evidences) {
        for (DomainEvidenceValidationPolicy policy : resolve(evidences)) {
            if (policy.skipGenericConflictDetection(evidences)) return true;
        }
        return false;
    }

    public Integer minEvidenceCountOverride(List<Evidence> evidences) {
        for (DomainEvidenceValidationPolicy policy : resolve(evidences)) {
            Integer value = policy.minEvidenceCountOverride(evidences);
            if (value != null && value > 0) return value;
        }
        return null;
    }

    public boolean isAuthoritativeEvidence(List<Evidence> evidences) {
        for (DomainEvidenceValidationPolicy policy : resolve(evidences)) {
            if (policy.isAuthoritativeEvidence(evidences)) return true;
        }
        return false;
    }

    public List<String> claimVerificationRules(List<Evidence> evidences) {
        List<String> rules = new ArrayList<>();
        for (DomainEvidenceValidationPolicy policy : resolve(evidences)) {
            List<String> contribution = policy.claimVerificationRules();
            if (contribution == null) continue;
            for (String rule : contribution) {
                if (rule != null && !rule.isBlank() && !rules.contains(rule)) rules.add(rule);
            }
        }
        return List.copyOf(rules);
    }
}
