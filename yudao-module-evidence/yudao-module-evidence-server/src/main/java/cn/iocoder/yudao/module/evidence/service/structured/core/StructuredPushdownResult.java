package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * Structured Adapter 对完整关系计划下推的执行结果。
 *
 * <p>UNSUPPORTED 不是失败：Core 必须回退现有行级执行路径。
 * SUCCEEDED 必须携带完整的 StructuredPipelineResult（包括 coverage/proof）。
 * FAILED 表示 Adapter 已接管该计划但权威后端执行失败，禁止再回退内存路径伪装成正常结果。</p>
 */
public record StructuredPushdownResult(Status status,
                                       StructuredPipelineResult result,
                                       String reason) {

    public enum Status {
        UNSUPPORTED,
        SUCCEEDED,
        FAILED
    }

    public static StructuredPushdownResult unsupported(String reason) {
        return new StructuredPushdownResult(Status.UNSUPPORTED, null, reason);
    }

    public static StructuredPushdownResult succeeded(StructuredPipelineResult result) {
        if (result == null) throw new IllegalArgumentException("pushdown result must not be null");
        return new StructuredPushdownResult(Status.SUCCEEDED, result, null);
    }

    public static StructuredPushdownResult failed(String reason) {
        return new StructuredPushdownResult(Status.FAILED, null,
                reason == null || reason.isBlank() ? "structured pushdown failed" : reason);
    }

    public boolean supported() {
        return status != Status.UNSUPPORTED;
    }
}
