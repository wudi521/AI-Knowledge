package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * 可选的整计划下推 SPI。
 *
 * <p>普通 {@link DomainStructuredDataAdapter} 继续提供完整行集，由 Core 在 JVM 中执行
 * filter/group/aggregate/order/limit。只有当领域数据源能够在权威存储层安全执行整个
 * {@link StructuredPipelinePlan} 时，才额外实现本接口。</p>
 *
 * <p>UNSUPPORTED 表示该计划不适合下推，Core 可以回退现有行级执行；FAILED 表示已选择
 * 下推但数据源执行失败，必须 fail-closed，禁止再用另一条路径把故障伪装成业务结果。</p>
 */
public interface StructuredPushdownAdapter extends DomainStructuredDataAdapter {

    /**
     * 尝试在权威数据源执行完整 Pipeline Plan。
     * 默认不支持，保证现有 Domain Adapter 零改动兼容。
     */
    default StructuredPushdownResult executePushdown(StructuredPipelinePlan plan) {
        return StructuredPushdownResult.unsupported("pushdown is not supported for this plan");
    }
}
