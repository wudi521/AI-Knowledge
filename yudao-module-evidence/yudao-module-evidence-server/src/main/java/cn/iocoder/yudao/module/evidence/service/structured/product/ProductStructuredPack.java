package cn.iocoder.yudao.module.evidence.service.structured.product;

import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.EntityDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Product Domain Pack(CQ-48 Fake PRODUCT): 向 Platform Core 注册产品实体/指标/字段。
 * <p>
 * 用于验证 Platform Core 的领域无关性: 新增领域仅需实现等价 Domain Pack + Adapter, Core 不修改。
 * 数据访问由 {@link ProductStructuredDataAdapter}(内存) 提供, 不依赖 KnowledgeApi。
 */
@Component
public class ProductStructuredPack {

    public static final String DOMAIN_CODE = "PRODUCT";
    public static final String ENTITY_PRODUCT = "PRODUCT";
    public static final String METRIC_PRODUCT_COUNT = "PRODUCT_COUNT";
    public static final String METRIC_PRICE = "PRICE";
    public static final String ADAPTER_KEY = "PRODUCT";
    public static final String FIELD_PRODUCT_NAME = "PRODUCT_NAME";
    public static final String FIELD_CATEGORY = "CATEGORY";
    public static final String FIELD_SKU = "SKU";

    public ProductStructuredPack(DomainMetricRegistry metricRegistry, DomainEntityRegistry entityRegistry,
                                 DomainFieldRegistry fieldRegistry) {
        entityRegistry.register(EntityDefinition.builder()
                .domainCode(DOMAIN_CODE).entityCode(ENTITY_PRODUCT)
                .displayLabel("产品").classifier("款")
                .aliases(List.of("产品", "商品", "型号"))
                .build());

        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_PRODUCT_COUNT).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_PRODUCT).valueType("INTEGER")
                .supportedOperations(Set.of(Operation.COUNT, Operation.COUNT_DISTINCT))
                .supportedGroupBy(List.of(ENTITY_PRODUCT))
                .aliases(List.of("产品数量", "商品数量", "产品个数", "产品数"))
                .displayName("产品").unit("款")
                .description("已登记产品数(COUNT=产品数)")
                .adapterKey(ADAPTER_KEY)
                .build());
        metricRegistry.register(MetricDefinition.builder()
                .metricCode(METRIC_PRICE).domainCode(DOMAIN_CODE)
                .entityType(ENTITY_PRODUCT).valueType("DECIMAL")
                .supportedOperations(Set.of(Operation.SUM, Operation.AVG, Operation.MIN, Operation.MAX))
                .supportedGroupBy(List.of(ENTITY_PRODUCT))
                .aliases(List.of("价格", "售价", "单价", "产品价格"))
                .displayName("价格").unit("元")
                .description("产品价格(SUM/AVG/MIN/MAX)")
                .adapterKey(ADAPTER_KEY)
                .build());

        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_PRODUCT_NAME).domainCode(DOMAIN_CODE).entityType(ENTITY_PRODUCT)
                .valueType("STRING").aliases(List.of("产品名", "名称"))
                .filterable(true).groupable(true).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_CATEGORY).domainCode(DOMAIN_CODE).entityType(ENTITY_PRODUCT)
                .valueType("STRING").aliases(List.of("品类", "分类", "类别"))
                .filterable(true).groupable(true).build());
        fieldRegistry.register(FieldDefinition.builder()
                .fieldCode(FIELD_SKU).domainCode(DOMAIN_CODE).entityType(ENTITY_PRODUCT)
                .valueType("STRING").aliases(List.of("SKU", "编码", "货号"))
                .sortable(true).filterable(true).build());
    }
}
