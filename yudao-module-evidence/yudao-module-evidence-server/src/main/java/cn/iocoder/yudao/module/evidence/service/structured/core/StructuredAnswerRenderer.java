package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Structured Answer Renderer(Platform Core 领域无关)。
 * <p>
 * 确定性答案: DB/完整数据集算出结果后由程序直接组句, 不交 LLM 复述。
 * 文案中的实体/指标名称来自 Domain Registry(displayName/unit/classifier), Core 不硬编码业务词。
 */
@Component
public class StructuredAnswerRenderer {

    public String render(StructuredQueryPlan plan, MetricDefinition metric, EntityDefinition entity,
                         StructuredQueryResult result) {
        if (result == null || result.isUnsupported()) return null;
        String displayName = metric != null ? metric.getDisplayName() : "指标";
        String unit = metric != null && StrUtil.isNotBlank(metric.getUnit()) ? metric.getUnit() : "";
        String entityLabel = entity != null && StrUtil.isNotBlank(entity.getDisplayLabel()) ? entity.getDisplayLabel() : "对象";
        String classifier = entity != null && StrUtil.isNotBlank(entity.getClassifier()) ? entity.getClassifier() : "";
        List<StructuredQueryResult.Row> rows = result.getRows() == null ? List.of() : result.getRows();

        QueryType type = plan.getQueryType();
        Operation op = plan.getOperation() == null ? Operation.NONE : plan.getOperation();
        boolean docSet = plan.getScope() != null
                && plan.getScope().getType() == QueryScopeType.DOCUMENT_SET;
        int n = plan.getScope() != null && plan.getScope().getResolvedEntityIds() != null
                ? plan.getScope().getResolvedEntityIds().size() : rows.size();

        // 精确对象(EXACT_LOOKUP)不在此渲染(走既有 EXACT 答案器)
        if (type == QueryType.EXACT_LOOKUP) return null;

        if (type == QueryType.LIST) {
            return renderList(rows, entityLabel, classifier, displayName);
        }
        if (type == QueryType.GROUP) {
            return renderGroup(rows, docSet, n, classifier, entityLabel, displayName, unit);
        }
        if (type == QueryType.TOP_N) {
            return renderTopN(plan, rows, displayName, unit, entityLabel, classifier);
        }

        switch (op) {
            case COUNT, COUNT_DISTINCT -> {
                long v = Math.round(result.getValue() == null ? 0 : result.getValue());
                return docSet
                        ? "这 " + n + " " + classifier + entityLabel + "，共 " + v + " 个。"
                        : "当前知识库共有 " + v + " " + classifier + "已发布" + entityLabel + "。";
            }
            case SUM -> {
                String v = formatNumber(result.getValue());
                if (docSet && rows.size() > 1) {
                    return "这 " + n + " " + classifier + entityLabel + "共有 " + v + " " + unit
                            + displayName + "，分别为 " + joinValues(rows, unit) + "。";
                }
                return "当前知识库共有 " + v + " " + unit + displayName + "。";
            }
            case AVG -> {
                String v = formatNumber(result.getValue());
                return docSet
                        ? "这 " + n + " " + classifier + entityLabel + "平均每" + classifier + "有 " + v + " " + unit + displayName + "。"
                        : "当前知识库平均每" + classifier + entityLabel + "有 " + v + " " + unit + displayName + "。";
            }
            case MAX, MIN -> {
                StructuredQueryResult.Row extreme = extremeRow(rows, op == Operation.MAX);
                String name = extreme != null && StrUtil.isNotBlank(extreme.getEntityName())
                        ? extreme.getEntityName() : "未知";
                String v = extreme != null ? formatNumber(extreme.getValue()) : "0";
                String word = op == Operation.MAX ? "最多" : "最少";
                return (docSet ? "这 " + n + " " + classifier + entityLabel + "中" : "当前知识库中")
                        + displayName + word + "的是 " + name + "（" + v + " " + unit + displayName + "）。";
            }
            default -> {
                return null;
            }
        }
    }

    private String renderList(List<StructuredQueryResult.Row> rows, String entityLabel,
                              String classifier, String displayName) {
        if (rows.isEmpty()) return "当前范围内没有已发布的" + entityLabel + "。";
        List<String> names = rows.stream().map(StructuredQueryResult.Row::getEntityName)
                .filter(StrUtil::isNotBlank).collect(Collectors.toList());
        if (names.size() > 8) {
            return "当前范围内已发布" + entityLabel + "共 " + rows.size() + " " + classifier + "：" + String.join("、", names.subList(0, 8)) + " 等。";
        }
        return "当前范围内已发布" + entityLabel + "共 " + rows.size() + " " + classifier + "：" + String.join("、", names) + "。";
    }

    private String renderGroup(List<StructuredQueryResult.Row> rows, boolean docSet, int n,
                               String classifier, String entityLabel, String displayName, String unit) {
        if (rows.isEmpty()) return "范围内没有可统计的" + displayName + "。";
        String prefix = docSet ? "这 " + n + " " + classifier + entityLabel + "的" + displayName + "数分别为："
                : "范围内各" + classifier + entityLabel + "的" + displayName + "数分别为：";
        String body = rows.stream()
                .map(r -> r.getEntityName() + "：" + formatNumber(r.getValue()) + " " + unit)
                .collect(Collectors.joining("；"));
        return prefix + body + "。";
    }

    private String renderTopN(StructuredQueryPlan plan, List<StructuredQueryResult.Row> rows,
                              String displayName, String unit, String entityLabel, String classifier) {
        if (rows.isEmpty()) return "范围内没有可统计的" + displayName + "。";
        boolean asc = plan.getSort() == SortDirection.ASC;
        String word = asc ? "最少" : "最多";
        int limit = plan.getLimit() == null ? 3 : plan.getLimit();
        StringBuilder sb = new StringBuilder();
        sb.append(displayName).append(word).append("的前 ").append(rows.size())
                .append(" ").append(classifier).append(entityLabel).append("：");
        int i = 1;
        for (StructuredQueryResult.Row r : rows) {
            sb.append(" ").append(i++).append(". ").append(r.getEntityName())
                    .append("（").append(formatNumber(r.getValue())).append(" ").append(unit).append("）");
        }
        return sb.toString().trim() + "。";
    }

    private StructuredQueryResult.Row extremeRow(List<StructuredQueryResult.Row> rows, boolean max) {
        if (rows.isEmpty()) return null;
        StructuredQueryResult.Row best = rows.get(0);
        for (StructuredQueryResult.Row r : rows) {
            double bv = best.getValue() == null ? 0 : best.getValue();
            double rv = r.getValue() == null ? 0 : r.getValue();
            if (max ? rv > bv : rv < bv) best = r;
        }
        return best;
    }

    private String joinValues(List<StructuredQueryResult.Row> rows, String unit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(i == rows.size() - 1 ? "和" : "、");
            sb.append(formatNumber(rows.get(i).getValue())).append(unit);
        }
        return sb.toString();
    }

    /** 整数不显示小数, 小数保留 1 位 */
    private String formatNumber(Double value) {
        if (value == null) return "0";
        double v = value;
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format("%.1f", v);
    }
}
