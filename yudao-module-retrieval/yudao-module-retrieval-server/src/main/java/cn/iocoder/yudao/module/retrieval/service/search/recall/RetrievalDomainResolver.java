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
 * 为 Recall Pipeline 解析领域作用域。
 *
 * <p>优先使用上游 Query Runtime 已确认的 domainCode；兼容旧调用方时，从 Knowledge Registry
 * 的 kbId -> domainCode 映射补齐。多领域混合 scope 不猜某一个专业领域，回退 GENERAL。</p>
 */
@Component
public class RetrievalDomainResolver {

    private final KnowledgeApi knowledgeApi;

    public RetrievalDomainResolver(KnowledgeApi knowledgeApi) {
        this.knowledgeApi = knowledgeApi;
    }

    public String resolve(String requestedDomainCode, List<Long> kbIds) {
        if (StrUtil.isNotBlank(requestedDomainCode)) {
            return DomainPluginContext.normalizeDomain(requestedDomainCode);
        }
        if (kbIds == null || kbIds.isEmpty()) return "GENERAL";
        try {
            Map<Long, String> mapping = knowledgeApi.getKbDomainCodes(kbIds).getCheckedData();
            if (mapping == null || mapping.isEmpty()) return "GENERAL";
            Set<String> domains = mapping.values().stream()
                    .filter(StrUtil::isNotBlank)
                    .map(DomainPluginContext::normalizeDomain)
                    .collect(Collectors.toSet());
            return domains.size() == 1 ? domains.iterator().next() : "GENERAL";
        } catch (Exception ignore) {
            return "GENERAL";
        }
    }
}
