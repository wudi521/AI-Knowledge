package cn.iocoder.yudao.module.evidence.service.validation;

import cn.iocoder.yudao.framework.common.plugin.DomainPipelinePlugin;
import cn.iocoder.yudao.module.evidence.domain.Evidence;

import java.util.List;

/**
 * 领域证据验证策略 SPI。
 *
 * <p>通用验证内核只负责冲突、充分性、Claim 支撑等稳定流程；行业差异通过本 SPI 注入。
 * 新增行业只新增插件，不允许在 ConflictDetector/SufficiencyJudge/ClaimVerifier 中增加行业 if/else。</p>
 */
public interface DomainEvidenceValidationPolicy extends DomainPipelinePlugin {

    /** 某领域是否应跳过通用冲突模型。 */
    default boolean skipGenericConflictDetection(List<Evidence> evidences) {
        return false;
    }

    /** 行业可覆盖最小证据数；null 表示使用平台通用配置。 */
    default Integer minEvidenceCountOverride(List<Evidence> evidences) {
        return null;
    }

    /** 是否存在可以单条成立的权威原文证据。 */
    default boolean isAuthoritativeEvidence(List<Evidence> evidences) {
        return false;
    }

    /** 追加给 ClaimVerifier 的行业核查规则；不得替换平台通用证据约束。 */
    default List<String> claimVerificationRules() {
        return List.of();
    }
}
