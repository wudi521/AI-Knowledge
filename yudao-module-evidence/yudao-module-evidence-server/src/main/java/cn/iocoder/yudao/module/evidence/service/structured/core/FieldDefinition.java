package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * FieldDefinition(结构化字段定义, CQ-11)
 * <p>
 * Metric(数值聚合)与 Field(维度/字段)区分。Field 同时声明真实可执行能力：过滤、排序、分组、
 * 多值展开以及允许的受控值变换。Domain Schema 是 Planner 与 Executor 的共同 Source of Truth。
 */
@Data
@Builder
public class FieldDefinition {

    private String fieldCode;
    private String domainCode;
    private String entityType;
    /** STRING / INTEGER / DECIMAL / DATE */
    private String valueType;
    /** 中文别名(公布号/公开编号 等), 最长匹配优先 */
    private List<String> aliases;
    /** 该字段允许的过滤运算符；空集合表示不可结构化过滤。 */
    private Set<FilterOperator> allowedOperators;
    /** 是否是可唯一定位实体的业务标识符。 */
    private boolean exactIdentifier;
    /** 标识符的领域正则集合；由确定性解析器读取。 */
    private List<String> identifierPatterns;
    /** 单实体是否多值(如多个申请人/发明人)。 */
    private boolean multiValue;
    /**
     * 多值字段物理存储的分隔契约。只有 multiValue=true 时生效。
     * Adapter 合并、Executor explode/filter/group/count/transform 必须共用该规则，禁止各写一套分隔逻辑。
     */
    @Builder.Default
    private String multiValueDelimiterRegex = MultiValueSupport.DEFAULT_DELIMITER_REGEX;
    private boolean sortable;
    private boolean filterable;
    private boolean groupable;

    /**
     * 当前字段真正允许的派生运算。未声明即不可由 Planner 调用，避免 Schema 声明与执行能力脱节。
     */
    @Builder.Default
    private Set<StructuredValueTransform> allowedTransforms = Collections.emptySet();
}
