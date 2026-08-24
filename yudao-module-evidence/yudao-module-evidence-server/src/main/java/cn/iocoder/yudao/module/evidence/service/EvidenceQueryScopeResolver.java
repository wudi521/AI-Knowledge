package cn.iocoder.yudao.module.evidence.service;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evidence/Chat 统一查询范围解析器。
 * <p>
 * 所有入口在进入 Query Planner / Structured / Retrieval 前统一完成：
 * 用户可见 KB 裁剪、显式 KB fail-closed、Domain 自动解析、跨领域拒绝。
 * /evidence/evaluate 与 chat RPC 因而只在 history/context 是否存在上有差异，查询内核完全一致。
 */
@Slf4j
@Component
public class EvidenceQueryScopeResolver {

    private final KnowledgeApi knowledgeApi;

    public EvidenceQueryScopeResolver(KnowledgeApi knowledgeApi) {
        this.knowledgeApi = knowledgeApi;
    }

    public record Resolution(List<Long> kbIds, String domainCode, boolean allowed, String reasonCode, String message) {
        public static Resolution denied(String reasonCode, String message) {
            return new Resolution(List.of(), null, false, reasonCode, message);
        }
    }

    public Resolution resolve(List<Long> requestedKbIds, Long userId, String requestedDomainCode) {
        if (userId == null) {
            return Resolution.denied("MISSING_USER_CONTEXT", "缺少用户身份，无法安全确定可检索知识库范围。");
        }
        try {
            Set<Long> visible = knowledgeApi.getVisibleKbIds(userId).getCheckedData();
            if (visible == null || visible.isEmpty()) {
                return Resolution.denied("NO_VISIBLE_KB", "当前用户没有可访问的知识库。");
            }

            List<Long> requested = requestedKbIds == null ? List.of() : requestedKbIds.stream()
                    .filter(java.util.Objects::nonNull).distinct().toList();
            List<Long> effective;
            if (requested.isEmpty()) {
                effective = visible.stream().sorted().toList();
            } else {
                // 显式请求知识库时 fail-closed：不能静默丢掉无权限 KB 后继续回答剩余部分。
                List<Long> denied = requested.stream().filter(id -> !visible.contains(id)).toList();
                if (!denied.isEmpty()) {
                    return Resolution.denied("KB_PERMISSION_DENIED", "所选知识库中存在当前用户无权访问的范围。");
                }
                effective = requested;
            }
            if (effective.isEmpty()) {
                return Resolution.denied("NO_VISIBLE_KB", "当前查询没有可访问的知识库范围。");
            }

            Map<Long, String> domainMap = knowledgeApi.getKbDomainCodes(effective).getCheckedData();
            LinkedHashSet<String> domains = new LinkedHashSet<>();
            if (domainMap != null) {
                for (Long kbId : effective) {
                    String code = domainMap.get(kbId);
                    domains.add(StrUtil.blankToDefault(code, "GENERAL").toUpperCase());
                }
            }
            if (domains.isEmpty()) domains.add("GENERAL");
            if (domains.size() > 1) {
                return Resolution.denied("MIXED_DOMAIN_SCOPE", "一次查询只能选择同一领域的知识库，请缩小范围后重试。");
            }
            String resolvedDomain = domains.iterator().next();
            if (StrUtil.isNotBlank(requestedDomainCode)
                    && !resolvedDomain.equalsIgnoreCase(requestedDomainCode)) {
                log.warn("[resolve][domain mismatch requested={}, resolved={}, kbIds={}]",
                        requestedDomainCode, resolvedDomain, effective);
                return Resolution.denied("DOMAIN_SCOPE_MISMATCH", "查询领域与所选知识库领域不一致，请重新选择范围。");
            }
            return new Resolution(new ArrayList<>(effective), resolvedDomain, true, null, null);
        } catch (Exception e) {
            log.warn("[resolve][query scope resolution failed: {}]", e.getMessage());
            return Resolution.denied("SCOPE_RESOLUTION_FAILED", "查询范围解析失败，请稍后重试。");
        }
    }
}
