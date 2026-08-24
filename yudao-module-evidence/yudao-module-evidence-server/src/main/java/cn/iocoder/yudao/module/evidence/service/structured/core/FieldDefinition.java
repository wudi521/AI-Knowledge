package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;

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
    /** 单实体是否多值(如 多个申请人) */
    private boolean multiValue;
    private boolean sortable;
    private boolean filterable;
    private boolean groupable;

}
