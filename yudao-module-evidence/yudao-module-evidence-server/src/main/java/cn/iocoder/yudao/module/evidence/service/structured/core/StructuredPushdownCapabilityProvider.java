package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.List;

/**
 * 机器可读下推能力目录 SPI。
 *
 * <p>目录负责告诉 Planner/运营台“已经证明支持什么”；真正执行仍由 StructuredPushdownAdapter.supports(plan)
 * 做最终判定，两者分离可以避免把规划提示当成安全边界。</p>
 */
public interface StructuredPushdownCapabilityProvider {
    String domainCode();
    List<StructuredPushdownCapability> capabilities();
}
