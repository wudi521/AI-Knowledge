package cn.iocoder.yudao.module.evidence.service.agent;

/**
 * Agent V1.1 单步执行轨迹。
 *
 * <p>保存安全参数摘要用于回放；不保存完整 Prompt、密钥、权限或服务端 scope。</p>
 */
public record AgentTraceStep(int seq,
                             String phase,
                             String action,
                             String capability,
                             String purpose,
                             String argumentsSummary,
                             String status,
                             long elapsedMs,
                             String summary,
                             AgentStopReason stopReason) {
    /** 兼容旧测试/构造。 */
    public AgentTraceStep(int seq, String phase, String action, String capability,
                          String purpose, String status, long elapsedMs, String summary,
                          AgentStopReason stopReason) {
        this(seq, phase, action, capability, purpose, null, status, elapsedMs, summary, stopReason);
    }
}
