package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.module.evidence.service.structured.core.DataGrain;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.EntityDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.MultiValueSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScopeType;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueTransform;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.BETWEEN;
import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.CONTAINS;
import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.EQ;
import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.EXISTS;
import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.GT;
import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.GTE;
import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.IN;
import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.LT;
import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.LTE;
import static cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.STARTS_WITH;

/** Patent Domain Pack：只向 Planner 注册当前真实可执行的专利实体、指标和字段能力。 */
@Component
public class PatentStructuredPack {

    public static final String DOMAIN_CODE = "PATENT";
    public static final String ENTITY_PATENT_DOCUMENT = "PATENT_DOCUMENT";
    public static final String ENTITY_CLAIM = "CLAIM";
    public static final String METRIC_PATENT_COUNT = "PATENT_COUNT";
    public static final String METRIC_DOCUMENT_COUNT = "DOCUMENT_COUNT";
    public static final String METRIC_CLAIM_COUNT = "CLAIM_COUNT";
    /** 常量保留给未来 Domain Pack；在数据适配器真正支持前不得注册给 Planner。 */
    public static final String METRIC_INDEPENDENT_CLAIM_COUNT = "INDEPENDENT_CLAIM_COUNT";
    public static final String METRIC_DEPENDENT_CLAIM_COUNT = "DEPENDENT_CLAIM_COUNT";
    public static final String ADAPTER_KEY = "PATENT";

    public static final String FIELD_PUBLICATION_NO = "PUBLICATION_NO";
    public static final String FIELD_APPLICATION_NO = "APPLICATION_NO";
    public static final String FIELD_APPLICANT = "APPLICANT";
    public static final String FIELD_INVENTOR = "INVENTOR";
    public static final String FIELD_TITLE = "TITLE";
    public static final String FIELD_FILING_DATE = "FILING_DATE";
    public static final String FIELD_PUBLICATION_DATE = "PUBLICATION_DATE";

    /** 专利解析器现实数据里既存在标点分隔，也存在全角空格/多空格分隔。 */
    public static final String PERSON_OR_ORG_MULTI_VALUE_DELIMITER = MultiValueSupport.DEFAULT_DELIMITER_REGEX;

    public PatentStructuredPack(DomainMetricRegistry metricRegistry, DomainEntityRegistry entityRegistry,
                                DomainFieldRegistry fieldRegistry) {
        entityRegistry.register(EntityDefinition.builder()
                .domainCode(DOMAIN_CODE).entityCode(ENTITY_PATENT_DOCUMENT)
                .displayLabel("专利文献").classifier("件")
                .aliases(List.of("专利", "文献", "文档", "专利文献", "专利文档")).build());
        entityRegistry.register(EntityDefinition.builder()
                .domainCode(DOMAIN_CODE).entityCode(ENTITY_CLAIM)
                .displayLabel("权利要求").classifier("项")
                .aliases(List.of("权利要求", "权项", "专利要求")).build());

        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_PATENT_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_PATENT_DOCUMENT).dataGrain(DataGrain.LOGICAL_ENTITY).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.COUNT)).supportedGroupBy(List.of(ENTITY_PATENT_DOCUMENT))
                .aliases(List.of("专利数量", "专利个数", "专利数", "专利总数", "多少个专利", "几个专利", "多少件专利"))
                .displayName("专利").unit("件")
                .description("已发布独立专利实体数；Domain Adapter 按申请号优先、公布号兜底去重")
                .adapterKey(ADAPTER_KEY).build());
        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_DOCUMENT_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_PATENT_DOCUMENT).dataGrain(DataGrain.SOURCE_RECORD).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.COUNT)).supportedGroupBy(List.of(ENTITY_PATENT_DOCUMENT))
                .aliases(List.of("专利文献数量", "专利文档数量", "文档数量", "文档数", "文件数量", "文件数"))
                .displayName("专利文档").unit("份").description("已发布物理知识文档记录数；每个知识库文档记录独立计数，不做逻辑专利去重")
                .adapterKey(ADAPTER_KEY).build());
        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_CLAIM_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_PATENT_DOCUMENT).dataGrain(DataGrain.LOGICAL_ENTITY).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.SUM, Operation.AVG, Operation.MIN, Operation.MAX))
                .supportedGroupBy(List.of(ENTITY_PATENT_DOCUMENT))
                .aliases(List.of("权利要求数量", "权项数量", "权项数", "专利要求数量", "专利要求", "权利要求"))
                .displayName("权利要求").unit("项").description("单件逻辑专利的权利要求数")
                .adapterKey(ADAPTER_KEY).build());

        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_PUBLICATION_NO).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").aliases(List.of("公布号", "公开编号", "公开号"))
                .allowedOperators(Set.of(EQ, IN, EXISTS)).exactIdentifier(true)
                .identifierPatterns(PatentIdentifierSupport.publicationPatterns())
                .sortable(true).filterable(true).groupable(true)
                .allowedTransforms(Set.of(StructuredValueTransform.LENGTH)).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_APPLICATION_NO).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").aliases(List.of("申请号", "申请编号", "专利号"))
                .allowedOperators(Set.of(EQ, IN, EXISTS)).exactIdentifier(true)
                .identifierPatterns(PatentIdentifierSupport.applicationPatterns())
                .sortable(true).filterable(true).groupable(true)
                .allowedTransforms(Set.of(StructuredValueTransform.LENGTH)).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_APPLICANT).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").multiValue(true)
                .multiValueDelimiterRegex(PERSON_OR_ORG_MULTI_VALUE_DELIMITER)
                .aliases(List.of("申请人", "申请单位"))
                .allowedOperators(Set.of(EQ, IN, CONTAINS, EXISTS))
                .sortable(true).filterable(true).groupable(true)
                .allowedTransforms(Set.of(StructuredValueTransform.LENGTH, StructuredValueTransform.VALUE_COUNT)).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_INVENTOR).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").multiValue(true)
                .multiValueDelimiterRegex(PERSON_OR_ORG_MULTI_VALUE_DELIMITER)
                .aliases(List.of("发明人", "发明者"))
                .allowedOperators(Set.of(EQ, IN, CONTAINS, EXISTS))
                .sortable(true).filterable(true).groupable(true)
                .allowedTransforms(Set.of(StructuredValueTransform.LENGTH, StructuredValueTransform.VALUE_COUNT,
                        StructuredValueTransform.PERSON_SURNAME)).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_TITLE).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").aliases(List.of("标题", "专利名称", "发明名称"))
                .allowedOperators(Set.of(EQ, CONTAINS, STARTS_WITH, EXISTS))
                .sortable(true).filterable(true).groupable(true)
                .allowedTransforms(Set.of(StructuredValueTransform.LENGTH)).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_FILING_DATE).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("DATE").aliases(List.of("申请日", "申请日期"))
                .allowedOperators(Set.of(EQ, IN, EXISTS, GT, GTE, LT, LTE, BETWEEN))
                .sortable(true).filterable(true).groupable(true)
                .allowedTransforms(Set.of(StructuredValueTransform.YEAR, StructuredValueTransform.MONTH,
                        StructuredValueTransform.YEAR_MONTH)).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_PUBLICATION_DATE).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("DATE").aliases(List.of("公开日", "公告日", "公开日期"))
                .allowedOperators(Set.of(EQ, IN, EXISTS, GT, GTE, LT, LTE, BETWEEN))
                .sortable(true).filterable(true).groupable(true)
                .allowedTransforms(Set.of(StructuredValueTransform.YEAR, StructuredValueTransform.MONTH,
                        StructuredValueTransform.YEAR_MONTH)).build());
    }

    @SuppressWarnings("unused")
    public static final QueryScopeType[] SUPPORTED_SCOPES = {
            QueryScopeType.CURRENT_KB, QueryScopeType.DOCUMENT_SET, QueryScopeType.CONVERSATION_CONTEXT
    };
}
