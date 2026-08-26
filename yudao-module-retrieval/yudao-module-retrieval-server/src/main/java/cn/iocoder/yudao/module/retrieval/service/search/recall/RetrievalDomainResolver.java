package cn.iocoder.yudao.module.retrieval.service.search.recall;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 为 Retrieval Pipeline 解析领域作用域。
 *
 * <p>上游已确认 domainCode 时不再远程猜测；兼容旧调用方时从 Knowledge Registry 获取 kbId -> domainCode。
 * Registry 访问失败与“GENERAL”不是同一个事实：失败必须显式上报，调用方 fail-closed，禁止专业领域门禁被静默跳过。</p>
 */
@Component
public class RetrievalDomainResolver {

    private final KnowledgeApi knowledgeApi;

    public RetrievalDomainResolver(KnowledgeApi knowledgeApi) {
        this.knowledgeApi = knowledgeApi;
    }

    /** 兼容旧调用；新 Pipeline 应使用 resolveWithStatus 并处理 failed。 */
    public String resolve(String requestedDomainCode, List<Long> kbIds) {
        return resolveWithStatus(requestedDomainCode, kbIds).domainCode();
    }

    public Resolution resolveWithStatus(String requestedDomainCode, List<Long> kbIds) {
        if (StrUtil.isNotBlank(requestedDomainCode)) {
            return Resolution.success(DomainPluginContext.normalizeDomain(requestedDomainCode), false);
        }
        if (kbIds == null || kbIds.isEmpty()) return Resolution.success("GENERAL", false);
        try {
            Map<Long, String> mapping = knowledgeApi.getKbDomainCodes(kbIds).getCheckedData();
            if (mapping == null || mapping.isEmpty()) {
                return Resolution.failure("knowledge registry returned no domain mapping for visible kb scope");
            }
            Set<String> domains = mapping.values().stream()
                    .filter(StrUtil::isNotBlank)
                    .map(DomainPluginContext::normalizeDomain)
                    .collect(Collectors.toSet());
            if (domains.isEmpty()) return Resolution.success("GENERAL", false); // legacy KB without domainCode
            if (domains.size() == 1) return Resolution.success(domains.iterator().next(), false);
            // 混合领域不猜专业插件；仅使用通用能力，并把 mixed 标记进 trace。
            return Resolution.success("GENERAL", true);
        } catch (Exception e) {
            return Resolution.failure("knowledge domain resolution failed: " + e.getClass().getSimpleName());
        }
    }

    public record Resolution(String domainCode,
                             boolean failed,
                             boolean mixedDomainScope,
                             String message) {
        public Resolution {
            domainCode = DomainPluginContext.normalizeDomain(domainCode);
        }

        public static Resolution success(String domainCode, boolean mixed) {
            return new Resolution(domainCode, false, mixed, null);
        }

        public static Resolution failure(String message) {
            return new Resolution("GENERAL", true, false, message);
        }
    }
}
