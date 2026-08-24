package cn.iocoder.yudao.module.evidence.service.structured.product;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityResolver;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainStructuredDataAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScopeType;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Product Structured Data Adapter(CQ-48 Fake PRODUCT): 内存数据适配器。
 * <p>
 * 不依赖 KnowledgeApi(纯内存), 用于验证 Platform Core 领域无关性: 新增领域无需修改 Core。
 * 支持: PRODUCT_COUNT / PRICE 指标, PRODUCT_NAME/CATEGORY/SKU 字段, DOCUMENT_SET 范围过滤,
 * 以及产品名/SKU 的实体抽取与定位。
 */
@Component
public class ProductStructuredDataAdapter implements DomainStructuredDataAdapter, DomainEntityResolver {

    private record Product(Long id, String name, String category, double price, String sku) {
    }

    private static final List<Product> PRODUCTS = List.of(
            new Product(201L, "手机A", "手机", 3999, "SKU-A01"),
            new Product(202L, "手机B", "手机", 4999, "SKU-A02"),
            new Product(203L, "耳机C", "耳机", 999, "SKU-B01"));

    @Override
    public String adapterKey() {
        return ProductStructuredPack.ADAPTER_KEY;
    }

    @Override
    public boolean supports(String metricCode) {
        if (metricCode == null) {
            return false;
        }
        return ProductStructuredPack.METRIC_PRODUCT_COUNT.equals(metricCode)
                || ProductStructuredPack.METRIC_PRICE.equals(metricCode)
                || ProductStructuredPack.FIELD_PRODUCT_NAME.equals(metricCode)
                || ProductStructuredPack.FIELD_CATEGORY.equals(metricCode)
                || ProductStructuredPack.FIELD_SKU.equals(metricCode);
    }

    @Override
    public String domainCode() {
        return ProductStructuredPack.DOMAIN_CODE;
    }

    @Override
    public StructuredQueryResult execute(StructuredQueryPlan plan) {
        if (plan == null || plan.getScope() == null) {
            return StructuredQueryResult.unsupported("scope 未确定");
        }
        List<Product> scope = applyScope(plan);
        String fieldCode = plan.getFieldCode();
        List<StructuredQueryResult.Row> rows = new ArrayList<>(scope.size());
        for (Product p : scope) {
            String key = fieldValueOf(p, fieldCode);
            Double value;
            if (fieldCode != null) {
                value = null;
            } else if (ProductStructuredPack.METRIC_PRODUCT_COUNT.equals(plan.getMetricCode())) {
                value = 1d;
            } else if (ProductStructuredPack.METRIC_PRICE.equals(plan.getMetricCode())) {
                value = p.price();
            } else {
                value = null;
            }
            rows.add(StructuredQueryResult.Row.builder()
                    .entityId(p.id())
                    .entityKey(StrUtil.isNotBlank(key) ? key : p.sku())
                    .entityName(p.name() + (StrUtil.isNotBlank(key) ? " · " + key : ""))
                    .value(value)
                    .build());
        }
        return StructuredQueryResult.builder()
                .metricCode(plan.getMetricCode())
                .operation(plan.getOperation())
                .rows(rows)
                .rowCount(rows.size())
                .truncated(false)
                .build();
    }

    /** 范围过滤: DOCUMENT_SET(显式实体集) 限定产品; 其余(当前 KB/历史上下文)返回全部 */
    private List<Product> applyScope(StructuredQueryPlan plan) {
        QueryScope scope = plan.getScope();
        if (QueryScopeType.DOCUMENT_SET.equals(scope.getType()) && scope.getResolvedEntityIds() != null
                && !scope.getResolvedEntityIds().isEmpty()) {
            Set<Long> ids = new HashSet<>(scope.getResolvedEntityIds());
            return PRODUCTS.stream().filter(p -> ids.contains(p.id())).toList();
        }
        return PRODUCTS;
    }

    private String fieldValueOf(Product p, String fieldCode) {
        if (fieldCode == null) {
            return null;
        }
        return switch (fieldCode) {
            case ProductStructuredPack.FIELD_PRODUCT_NAME -> p.name();
            case ProductStructuredPack.FIELD_CATEGORY -> p.category();
            case ProductStructuredPack.FIELD_SKU -> p.sku();
            default -> null;
        };
    }

    // ========== DomainEntityResolver: 从文本抽取产品名/SKU 并定位 ==========

    @Override
    public List<ResolvedEntity> extractEntities(String text) {
        List<ResolvedEntity> result = new ArrayList<>();
        if (StrUtil.isBlank(text)) {
            return result;
        }
        for (Product p : PRODUCTS) {
            if (text.contains(p.name()) || text.contains(p.sku())) {
                result.add(new ResolvedEntity(p.name(), null, null));
            }
        }
        return result;
    }

    @Override
    public List<ResolvedEntity> resolveToEntities(List<ResolvedEntity> entities, Long kbId) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        Set<ResolvedEntity> resolved = new LinkedHashSet<>();
        for (ResolvedEntity e : entities) {
            if (e == null || e.identifier() == null) {
                continue;
            }
            for (Product p : PRODUCTS) {
                if (e.identifier().equals(p.name()) || e.identifier().equals(p.sku())) {
                    resolved.add(new ResolvedEntity(e.identifier(), p.id(), p.name()));
                }
            }
        }
        return new ArrayList<>(resolved);
    }
}
