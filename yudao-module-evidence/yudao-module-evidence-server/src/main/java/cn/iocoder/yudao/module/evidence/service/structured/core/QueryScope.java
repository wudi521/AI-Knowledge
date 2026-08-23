package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Structured Query 范围(Platform Core 领域无关)。
 * <p>
 * 范围必须可审计: CURRENT_KB / DOCUMENT_SET / ENTITY_SET 等, 由 Context Resolver 消解
 * "这个/这些/三个/它们/上述/前面的/刚才那几份/其中" 等指代, 禁止无法消解时随机选择对象。
 */
@Data
@Builder
public class QueryScope {

    /** 范围类型 */
    private QueryScopeType type;

    /** 当前知识库编号(CURRENT_KB / DOCUMENT_SET 的宿主 KB) */
    private Long currentKbId;

    /** 已消解的实体集合(DOCUMENT_SET / ENTITY_SET 的 documentId/entityId; 空 = 未消解) */
    private List<Long> resolvedEntityIds;

    /** 时间范围(预留) */
    private String timeRange;

    /** 地域范围(预留) */
    private String region;

    public static QueryScope currentKb(Long kbId) {
        return QueryScope.builder().type(QueryScopeType.CURRENT_KB).currentKbId(kbId).build();
    }

    public static QueryScope documentSet(Long kbId, List<Long> ids) {
        return QueryScope.builder().type(QueryScopeType.DOCUMENT_SET).currentKbId(kbId)
                .resolvedEntityIds(ids == null ? List.of() : ids).build();
    }
}
