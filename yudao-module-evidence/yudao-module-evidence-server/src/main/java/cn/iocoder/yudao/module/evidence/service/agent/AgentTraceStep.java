package cn.iocoder.yudao.module.evidence.service.agent;

/**
 * Agent V1.1 单步执行轨迹。
 *
 * <p>只保存动作/能力/结果摘要等可审计信息，不保存完整 Prompt、密钥或权限上下文。</p>
 */
public record AgentTraceStep(int seq,
                             String phase,
                             String action,
                             String capability,
                             String purpose,
                             String status,
                             long elapsedMs,
                             String summary,
                             AgentStopReason stopReason) {
}
