package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.EntityDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScopeType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Patent Domain Pack(第一版): 向 Platform Core 注册专利实体与指标。
 * <p>
 * 本类只属于 Patent Domain Pack, Core 禁止反向引用本包。
 * 以后 Telecom/Manufacturing 各自实现等价 Domain Pack, Platform Core 不修改。
 */
@Component
public class PatentStructuredPack {

    public static final String DOMAIN_CODE = "PATENT";
    public static final String ENTITY_PATENT_DOCUMENT = "PATENT_DOCUMENT";
    public static final String ENTITY_CLAIM = "CLAIM";
    public static final String METRIC_DOCUMENT_COUNT = "DOCUMENT_COUNT";
    public static final String METRIC_CLAIM_COUNT = "CLAIM_COUNT";
    public static final String METRIC_INDEPENDENT_CLAIM_COUNT = "INDEPENDENT_CLAIM_COUNT";
    public static final String METRIC_DEPENDENT_CLAIM_COUNT = "DEPENDENT_CLAIM_COUNT";
    public static final String ADAPTER_KEY = "PATENT";

    public PatentStructuredPack(DomainMetricRegistry metricRegistry, DomainEntityRegistry entityRegistry) {
        // 实体
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

        // 指标: 数量类
        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_DOCUMENT_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_PATENT_DOCUMENT).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.COUNT, Operation.COUNT_DISTINCT))
                .supportedGroupBy(List.of(ENTITY_PATENT_DOCUMENT))
                .aliases(List.of("专利数量", "专利文献数量", "专利个数", "专利数", "文档数量"))
                .displayName("专利文献").unit("件")
                .description("已发布专利文献数(COUNT=文档数, COUNT_DISTINCT=按申请号去重数)")
                .adapterKey(ADAPTER_KEY)
                .build());
        // 指标: 权利要求数量(度量类)
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
        // 指标: 独立权利要求数量(数据源尚未提取独立/从属计数, 本轮定义但不支持执行)
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
    }

    /** Patent Domain Pack 支持的范围类型(Core 判断用; 不强制使用) */
    @SuppressWarnings("unused")
    public static final QueryScopeType[] SUPPORTED_SCOPES = {
            QueryScopeType.CURRENT_KB, QueryScopeType.DOCUMENT_SET, QueryScopeType.CONVERSATION_CONTEXT
    };
}
