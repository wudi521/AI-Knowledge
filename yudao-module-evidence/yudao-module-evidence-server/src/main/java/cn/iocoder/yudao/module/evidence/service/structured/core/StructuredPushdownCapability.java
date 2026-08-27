package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.List;

/**
 * 一个机器可读的整计划下推能力签名。
 *
 * <p>它描述“后端已经证明可以完整执行什么 typed 运算”，不是自然语言意图表。
 * Planner/运营台可以读取它来知道真实能力边界，Runtime 仍以 adapter.supports(plan) 作为最终安全判定。</p>
 */
public record StructuredPushdownCapability(String domainCode,
                                           String operation,
                                           String fieldCode,
                                           String metricCode,
                                           List<String> transforms,
                                           boolean grouped,
                                           boolean ordered,
                                           String resultShape,
                                           String backend) {
    public StructuredPushdownCapability {
        transforms = transforms == null ? List.of() : List.copyOf(transforms);
    }
}
