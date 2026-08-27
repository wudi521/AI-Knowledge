package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.List;

/**
 * 可选的整计划下推 SPI，与行级 {@link DomainStructuredDataAdapter} 正交。
 *
 * <p>实现类只在能够由权威存储层完整执行某个 {@link StructuredPipelinePlan} 时返回 supports=true。
 * UNSUPPORTED 可回退现有行级执行；FAILED 必须 fail-closed，禁止把后端故障伪装成业务空结果。</p>
 */
public interface StructuredPushdownAdapter {

    /** 插件所属领域，例如 PATENT。 */
    String domainCode();

    /**
     * 机器可读能力目录，供 Planner/运营台了解后端已经证明的 typed 能力边界。
     * 最终是否执行仍必须经过 supports(plan)，不能仅凭目录字符串放行。
     */
    default List<StructuredPushdownCapability> capabilities() {
        return List.of();
    }

    /** 当前完整 Pipeline Plan 是否可由该后端安全、完整地下推。 */
    boolean supports(StructuredPipelinePlan plan);

    /** 执行完整计划；supports=true 后的数据源失败必须返回 FAILED。 */
    StructuredPushdownResult executePushdown(StructuredPipelinePlan plan);
}
