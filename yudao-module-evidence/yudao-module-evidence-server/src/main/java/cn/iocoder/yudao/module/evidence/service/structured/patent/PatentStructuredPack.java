package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.EntityDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScopeType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Patent Domain Pack：注册专利实体、业务指标和结构化字段。 */
@Component
public class PatentStructuredPack {

    public static final String DOMAIN_CODE = "PATENT";
    public static final String ENTITY_PATENT_DOCUMENT = "PATENT_DOCUMENT";
    public static final String ENTITY_CLAIM = "CLAIM";

    /** 业务实体专利数：按申请号/公布号去重。 */
    public static final String METRIC_PATENT_COUNT = "PATENT_COUNT";
    /** 物理文档记录数：重复导入也分别计数。 */
    public static final String METRIC_DOCUMENT_COUNT = "DOCUMENT_COUNT";
    public static final String METRIC_CLAIM_COUNT = "CLAIM_COUNT";
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

    public PatentStructuredPack(DomainMetricRegistry metricRegistry, DomainEntityRegistry entityRegistry,
                                DomainFieldRegistry fieldRegistry) {
        entityRegistry.register(EntityDefinition.builder()
                .domainCode(DOMAIN_CODE).entityCode(ENTITY_PATENT_DOCUMENT)
                .displayLabel("专利文献").classifier("件")
                .aliases(List.of("专利", "文献", "文档", "专利文献", "专利文档"))
                .build());
        entityRegistry.register(EntityDefinition.builder()
                .domainCode(DOMAIN_CODE).entityCode(ENTITY_CLAIM)
                .displayLabel("权利要求").classifier("项")
                .aliases(List.of("权利要求", "权项", "专利要求"))
                .build());

        // “几个专利/专利总数”必须统计业务实体，不得把重复导入文档当成不同专利。
        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_PATENT_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_PATENT_DOCUMENT).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.COUNT))
                .supportedGroupBy(List.of(ENTITY_PATENT_DOCUMENT))
                .aliases(List.of("专利数量", "专利个数", "专利数", "专利总数", "多少个专利", "几个专利", "多少件专利"))
                .displayName("专利").unit("件")
                .description("已发布独立专利实体数；Domain Adapter 按申请号优先、公布号兜底去重")
                .adapterKey(ADAPTER_KEY)
                .build());

        // “文档数/文件数”才统计物理知识文档记录。
        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_DOCUMENT_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_PATENT_DOCUMENT).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.COUNT))
                .supportedGroupBy(List.of(ENTITY_PATENT_DOCUMENT))
                .aliases(List.of("专利文献数量", "专利文档数量", "文档数量", "文档数", "文件数量", "文件数"))
                .displayName("专利文档").unit("份")
                .description("已发布物理知识文档记录数；重复导入分别计数")
                .adapterKey(ADAPTER_KEY)
                .build());

        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_CLAIM_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_PATENT_DOCUMENT).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.SUM, Operation.AVG, Operation.MIN, Operation.MAX))
                .supportedGroupBy(List.of(ENTITY_PATENT_DOCUMENT))
                .aliases(List.of("权利要求数量", "权项数量", "权项数", "专利要求数量", "专利要求", "权利要求"))
                .displayName("权利要求").unit("项")
                .description("单件专利的权利要求数(SUM/AVG/MIN/MAX)")
                .adapterKey(ADAPTER_KEY)
                .build());
        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_INDEPENDENT_CLAIM_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_CLAIM).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.SUM, Operation.AVG, Operation.MIN, Operation.MAX))
                .supportedGroupBy(List.of(ENTITY_PATENT_DOCUMENT))
                .aliases(List.of("独立权利要求数量", "独立权项数"))
                .displayName("独立权利要求").unit("项")
                .description("独立权利要求数(待独立/从属关系数据提取)")
                .adapterKey(ADAPTER_KEY)
                .build());
        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_DEPENDENT_CLAIM_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_CLAIM).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.SUM, Operation.AVG, Operation.MIN, Operation.MAX))
                .supportedGroupBy(List.of(ENTITY_PATENT_DOCUMENT))
                .aliases(List.of("从属权利要求数量", "从属权项数"))
                .displayName("从属权利要求").unit("项")
                .description("从属权利要求数(待独立/从属关系数据提取)")
                .adapterKey(ADAPTER_KEY)
                .build());

        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_PUBLICATION_NO).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").aliases(List.of("公布号", "公开编号", "公开号"))
                .sortable(true).filterable(true).groupable(true).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_APPLICATION_NO).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").aliases(List.of("申请号", "专利号"))
                .sortable(true).filterable(true).groupable(true).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_APPLICANT).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").multiValue(true).aliases(List.of("申请人", "申请单位"))
                .filterable(true).groupable(true).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_INVENTOR).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").multiValue(true).aliases(List.of("发明人", "发明者"))
                .filterable(true).groupable(true).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_TITLE).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("STRING").aliases(List.of("标题", "专利名称", "发明名称"))
                .groupable(true).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_FILING_DATE).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("DATE").aliases(List.of("申请日", "申请日期"))
                .sortable(true).filterable(true).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_PUBLICATION_DATE).domainCode(DOMAIN_CODE).entityType(ENTITY_PATENT_DOCUMENT)
                .valueType("DATE").aliases(List.of("公开日", "公告日", "公开日期"))
                .sortable(true).filterable(true).build());
    }

    @SuppressWarnings("unused")
    public static final QueryScopeType[] SUPPORTED_SCOPES = {
            QueryScopeType.CURRENT_KB, QueryScopeType.DOCUMENT_SET, QueryScopeType.CONVERSATION_CONTEXT
    };
}
