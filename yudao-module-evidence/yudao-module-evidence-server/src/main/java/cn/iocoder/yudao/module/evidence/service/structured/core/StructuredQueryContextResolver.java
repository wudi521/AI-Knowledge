package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structured Query Context Resolver(Platform Core 领域无关)。
 * <p>
 * 消解 "这个/这些/三个/它们/上述/前面的/刚才那几份/其中" 等范围指代:
 * 结合 Conversation History / 上一次 QueryResult 中出现的实体标识(Domain Pack 负责抽取),
 * 得到明确 DOCUMENT_SET/ENTITY_SET。
 * <p>
 * 禁止: 用户说"三个对象"但无历史对象集合时, 随机选择前三个 —— 必须 CLARIFY。
 */
@Slf4j
@Component
public class StructuredQueryContextResolver {

    private final List<DomainEntityResolver> entityResolvers;

    public StructuredQueryContextResolver(List<DomainEntityResolver> entityResolvers) {
        this.entityResolvers = entityResolvers;
    }

    /** 范围消解结果 */
    public record ScopeResolution(QueryScope scope, boolean clarified, String clarificationQuestion) {
        public static ScopeResolution resolved(QueryScope scope) {
            return new ScopeResolution(scope, false, null);
        }

        public static ScopeResolution clarify(String question) {
            return new ScopeResolution(null, true, question);
        }
    }

    /**
     * 解析查询范围。
     *
     * @param pre         Level-1 候选信号
     * @param domainCode  领域编码(如 PATENT)
     * @param currentKbId 当前知识库
     * @param history     会话历史(USER/AI 轮次)
     * @return 明确范围或需反问
     */
    public ScopeResolution resolve(StructuredQueryPreParser.PreParsedQuery pre, String domainCode,
                                   Long currentKbId, List<ChatTurnDTO> history) {
        if (!pre.isScopeReference() && pre.getCardinality() == null) {
            // 无范围指代、无数量词 → 整库范围
            return ScopeResolution.resolved(QueryScope.currentKb(currentKbId));
        }
        DomainEntityResolver resolver = findResolver(domainCode);
        if (resolver == null) {
            return ScopeResolution.clarify(buildNoContextQuestion(pre.getQuery()));
        }
        List<DomainEntityResolver.ResolvedEntity> found = extractFromHistory(resolver, history);
        if (found.isEmpty()) {
            return ScopeResolution.clarify(buildNoContextQuestion(pre.getQuery()));
        }
        if (pre.getCardinality() != null && found.size() != pre.getCardinality()) {
            // 数量词(如 三个)与历史可定位对象数不一致 → 不能擅自圈定, 必须反问
            return ScopeResolution.clarify(
                    "你提到的对象与对话历史中已出现的对象数量不一致（历史中可定位 " + found.size()
                            + " 个），请明确你指的是哪几个对象？");
        }
        // 将标识符定位为具体对象(申请号/公布号 → documentId)
        List<DomainEntityResolver.ResolvedEntity> resolved =
                resolver.resolveToEntities(found, currentKbId);
        List<Long> ids = resolved.stream().map(DomainEntityResolver.ResolvedEntity::entityId)
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) {
            return ScopeResolution.clarify(buildNoContextQuestion(pre.getQuery()));
        }
        return ScopeResolution.resolved(QueryScope.documentSet(currentKbId, ids));
    }

    private List<DomainEntityResolver.ResolvedEntity> extractFromHistory(
            DomainEntityResolver resolver, List<ChatTurnDTO> history) {
        if (history == null || history.isEmpty()) return List.of();
        // 从最近轮次向前收集实体标识(保序, 去重)
        List<ChatTurnDTO> reversed = new ArrayList<>(history);
        java.util.Collections.reverse(reversed);
        Set<DomainEntityResolver.ResolvedEntity> seen = new LinkedHashSet<>();
        for (ChatTurnDTO turn : reversed) {
            if (turn == null || turn.getContent() == null) continue;
            List<DomainEntityResolver.ResolvedEntity> extracted = resolver.extractEntities(turn.getContent());
            if (extracted != null) {
                for (DomainEntityResolver.ResolvedEntity e : extracted) {
                    if (e != null && e.identifier() != null) seen.add(e);
                }
            }
        }
        return new ArrayList<>(seen);
    }

    private DomainEntityResolver findResolver(String domainCode) {
        if (domainCode == null) return null;
        return entityResolvers.stream()
                .filter(r -> domainCode.equalsIgnoreCase(r.domainCode()))
                .findFirst().orElse(null);
    }

    private String buildNoContextQuestion(String query) {
        return "你提到的对象（如“这些/几个”）在对话中没有对应的具体对象，请问你指的是哪几个对象？"
                + "请提供申请号、公布号，或在上一个问题中先指定具体文档。";
    }
}
