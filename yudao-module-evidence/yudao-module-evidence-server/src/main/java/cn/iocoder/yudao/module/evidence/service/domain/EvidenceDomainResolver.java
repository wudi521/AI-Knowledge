package cn.iocoder.yudao.module.evidence.service.domain;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;

import java.util.List;
import java.util.Locale;

/**
 * 从证据元数据解析统一领域代码。
 *
 * <p>只在整组证据能够一致确认同一领域时返回该领域；缺失、非法或混合领域一律退回 GENERAL，
 * 防止行业插件误作用于不可信或跨领域证据集。</p>
 */
public final class EvidenceDomainResolver {

    private static final String GENERAL = "GENERAL";

    private EvidenceDomainResolver() {
    }

    public static String resolve(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return GENERAL;
        String resolved = null;
        boolean sawEvidence = false;
        for (Evidence evidence : evidences) {
            if (evidence == null) continue;
            sawEvidence = true;
            if (StrUtil.isBlank(evidence.getChunkMetadata())) return GENERAL;
            try {
                String domain = JSONUtil.parseObj(evidence.getChunkMetadata()).getStr("domainCode");
                if (StrUtil.isBlank(domain)) return GENERAL;
                String normalized = domain.trim().toUpperCase(Locale.ROOT);
                if (resolved == null) {
                    resolved = normalized;
                } else if (!resolved.equals(normalized)) {
                    return GENERAL;
                }
            } catch (Exception e) {
                return GENERAL;
            }
        }
        return sawEvidence && StrUtil.isNotBlank(resolved) ? resolved : GENERAL;
    }
}
