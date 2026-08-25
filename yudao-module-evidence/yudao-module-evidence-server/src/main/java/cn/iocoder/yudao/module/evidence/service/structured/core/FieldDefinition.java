package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * FieldDefinition(结构化字段定义, CQ-11)
 * <p>
 * Metric(数值聚合)与 Field(维度/字段)区分: publicationNo/applicationNo/title/applicant/inventor/
 * filingDate/publicationDate 属于 Field(可 LIST/GROUP/FILTER/SORT); claimCount/price 属于 Metric(可聚合)。
 * Core 不硬编码字段, 由 Domain Pack 注册(aliases 中文同义)。
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
    /** 该字段允许的过滤运算符；空集合表示不可通过 V3 结构化过滤。 */
    private Set<FilterOperator> allowedOperators;
    /** 是否是可唯一定位实体的业务标识符。 */
    private boolean exactIdentifier;
    /** 标识符的领域正则集合；由通用确定性 Planner 读取，不在 Core 硬编码领域规则。 */
    private List<String> identifierPatterns;
    /** 单实体是否多值(如 多个申请人) */
    private boolean multiValue;
    private boolean sortable;
    private boolean filterable;
    private boolean groupable;

}
