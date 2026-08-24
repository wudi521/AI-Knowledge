package cn.iocoder.yudao.module.evidence.service.planner;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Planner 优先级门面：强语义先于“哪些/分别”等宽泛结构化信号。 */
@Component
public class QueryPlannerFacade {

    private static final Pattern QUOTED = Pattern.compile("[\"“‘']([^\"”’']{1,200})[\"”’']");
    private static final Pattern EXACT_SENTENCE = Pattern.compile(
            "(?:原文(?:中)?(?:是否)?(?:包含|出现|有)|精确搜索|精确匹配|查找原文)(?:短语|词语|文字)?[：:]?\\s*([^？?，,。；;]{1,120})");

    private final QueryPlannerV2 delegate;

    public QueryPlannerFacade(QueryPlannerV2 delegate) {
        this.delegate = delegate;
    }

    public QueryPlan plan(String query, String domainCode, List<ChatTurnDTO> history,
                          List<Long> explicitEntityIds, String contextResolutionJson) {
        List<Long> ids = explicitEntityIds == null ? List.of() : explicitEntityIds.stream().distinct().toList();

        // Exact Text 必须先于“哪些/出现”等宽泛候选，且必须有明确 phrase；否则反问，不降级向量搜索。
        if (isExactTextIntent(query)) {
            String phrase = extractExactText(query);
            if (StrUtil.isBlank(phrase)) {
                return QueryPlan.builder()
                        .queryClass(QueryClass.CLARIFY)
                        .executionMode(ExecutionMode.EXACT_TEXT_SEARCH)
                        .domainCode(domainCode)
                        .scopeType(ids.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                        .entityIds(ids)
                        .requiresClarification(true)
                        .clarificationQuestion("请明确要在原文中精确查找的词或短语，建议用引号括起来。")
                        .reasonCode("MISSING_EXACT_TEXT")
                        .plannerSource("DETERMINISTIC")
                        .build();
            }
            return QueryPlan.builder()
                    .queryClass(QueryClass.SEMANTIC_QUERY)
                    .executionMode(ExecutionMode.EXACT_TEXT_SEARCH)
                    .domainCode(domainCode)
                    .scopeType(ids.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                    .entityIds(ids)
                    .exactText(phrase)
                    .completenessPolicy(CompletenessPolicy.COMPLETE_REQUIRED)
                    .plannerSource("DETERMINISTIC")
                    .build();
        }

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

    private boolean isExactTextIntent(String query) {
        return StrUtil.isNotBlank(query) && StrUtil.containsAny(query,
                "原文出现", "原文中出现", "原文包含", "原文中包含", "精确搜索", "精确匹配", "查找原文");
    }

    private String extractExactText(String query) {
        if (StrUtil.isBlank(query)) return null;
        Matcher quoted = QUOTED.matcher(query);
        if (quoted.find()) return normalizePhrase(quoted.group(1));
        Matcher sentence = EXACT_SENTENCE.matcher(query);
        if (sentence.find()) return normalizePhrase(sentence.group(1));
        return null;
    }

    private String normalizePhrase(String value) {
        if (value == null) return null;
        String phrase = value.trim()
                .replaceAll("^(是否|有没有|有无|过|了|这个|这个词|这个短语)\\s*", "")
                .replaceAll("\\s*(吗|么|呢|？|\\?)$", "")
                .trim();
        return StrUtil.isBlank(phrase) ? null : StrUtil.maxLength(phrase, 200);
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
