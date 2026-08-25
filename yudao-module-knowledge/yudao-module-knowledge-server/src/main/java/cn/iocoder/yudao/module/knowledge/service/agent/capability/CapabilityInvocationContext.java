package cn.iocoder.yudao.module.knowledge.service.agent.capability;

/**
 * 能力调用的系统上下文。
 *
 * <p>这些字段由服务端注入，绝不能从 Planner 的 arguments 中读取。</p>
 */
public record CapabilityInvocationContext(Long tenantId,
                                          Long userId,
                                          Long kbId,
                                          String traceId) {
}
