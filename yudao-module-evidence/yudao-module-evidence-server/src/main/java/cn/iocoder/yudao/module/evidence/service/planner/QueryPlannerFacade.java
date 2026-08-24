package cn.iocoder.yudao.module.evidence.service.planner;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Planner 优先级门面：先处理会被“哪些/分别”误伤的强语义，再委托 QueryPlannerV2。
 */
@Component
public class QueryPlannerFacade {

    private final QueryPlannerV2 delegate;

    public QueryPlannerFacade(QueryPlannerV2 delegate) {
        this.delegate = delegate;
    }

    public QueryPlan plan(String query, String domainCode, List<ChatTurnDTO> history,
                          List<Long> explicitEntityIds, String contextResolutionJson) {
        List<Long> ids = explicitEntityIds == null ? List.of() : explicitEntityIds.stream().distinct().toList();
        ComparisonType comparison = comparison(query);
        if (comparison != ComparisonType.NONE) {
            return QueryPlan.builder()
                    .queryClass(QueryClass.SEMANTIC_QUERY)
                    .executionMode(ExecutionMode.CROSS_ENTITY_COMPARE)
                    .domainCode(domainCode)
                    .scopeType(ids.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                    .entityIds(ids)
                    .comparisonType(comparison)
                    .perEntityTopK(2)
                    .requireDistinctEntities(true)
                    .coveragePolicy("ALL")
                    .completenessPolicy(CompletenessPolicy.BEST_EFFORT)
                    .plannerSource("DETERMINISTIC")
                    .build();
        }
        if (!ids.isEmpty() && perEntitySemantic(query)) {
            return QueryPlan.builder()
                    .queryClass(QueryClass.SEMANTIC_QUERY)
                    .executionMode(ExecutionMode.PER_ENTITY_SEMANTIC)
                    .domainCode(domainCode)
                    .scopeType("PREVIOUS_RESULT_SET")
                    .entityIds(ids)
                    .perEntityTopK(4)
                    .coveragePolicy("ALL")
                    .completenessPolicy(CompletenessPolicy.BEST_EFFORT)
                    .plannerSource("DETERMINISTIC")
                    .build();
        }
        return delegate.plan(query, domainCode, history, contextResolutionJson);
    }

    private ComparisonType comparison(String query) {
        if (StrUtil.isBlank(query)) return ComparisonType.NONE;
        if (StrUtil.containsAny(query, "最相似", "最像", "最接近", "类似")) return ComparisonType.SIMILARITY;
        if (StrUtil.containsAny(query, "共同点", "共性", "相同点")) return ComparisonType.COMMONALITY;
        if (StrUtil.containsAny(query, "区别", "差异", "不同点")) return ComparisonType.DIFFERENCE;
        if (StrUtil.containsAny(query, "相似", "比较", "对比")) return ComparisonType.PAIR_COMPARE;
        return ComparisonType.NONE;
    }

    private boolean perEntitySemantic(String query) {
        if (StrUtil.isBlank(query)) return false;
        boolean semantic = StrUtil.containsAny(query, "核心技术", "技术方案", "技术原理", "解决什么", "背景技术",
                "实施例", "实施方式", "主要内容", "总结", "概括");
        boolean perEntity = StrUtil.containsAny(query, "分别", "各自", "逐个", "每个", "它们", "这些", "这几个");
        return semantic && perEntity;
    }
}
