package cn.iocoder.yudao.module.evidence.service.agent.capability;

/** 由服务端注入；Planner 无权指定或覆盖。 */
public record CapabilityInvocationContext(Long tenantId,
                                          Long userId,
                                          Long kbId,
                                          String domainCode,
                                          String traceId) {
}
