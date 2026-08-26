package cn.iocoder.yudao.module.evidence.service.validation.patent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.validation.DomainEvidenceValidationPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** PATENT 证据验证策略：只承载专利领域差异，不污染平台验证内核。 */
@Component
public class PatentEvidenceValidationPolicy implements DomainEvidenceValidationPolicy {

    @Override
    public String pluginId() {
        return "validation:patent";
    }

    @Override
    public Set<String> supportedDomains() {
        return Set.of("PATENT");
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean skipGenericConflictDetection(List<Evidence> evidences) {
        // 不同专利、权利要求、实施例之间的差异是正常研究对象，不使用客服政策式冲突门禁。
        return evidences != null && !evidences.isEmpty();
    }

    @Override
    public Integer minEvidenceCountOverride(List<Evidence> evidences) {
        return isAuthoritativeEvidence(evidences) ? 1 : null;
    }

    @Override
    public boolean isAuthoritativeEvidence(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return false;
        for (Evidence evidence : evidences) {
            if (evidence == null || StrUtil.isBlank(evidence.getChunkMetadata())) continue;
            try {
                String section = JSONUtil.parseObj(evidence.getChunkMetadata()).getStr("sectionType");
                if ("CLAIMS".equalsIgnoreCase(section) || "BIBLIOGRAPHIC".equalsIgnoreCase(section)) return true;
            } catch (Exception ignore) {
                // 非法 metadata 不提升为权威证据。
            }
        }
        return false;
    }

    @Override
    public List<String> claimVerificationRules() {
        return List.of("专利公开文本中的医疗/科学效果若回答明确表述为‘文献记载/声称，不能据此确认真实性/疗效/安全性’，其中谨慎性限制句允许 evidenceIndex=-1。");
    }
}
