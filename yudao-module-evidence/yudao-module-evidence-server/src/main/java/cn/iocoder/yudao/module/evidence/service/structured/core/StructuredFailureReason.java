package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * Structured 失败/澄清原因码(CQ-38)。
 * <p>
 * 结构化查询无法可靠作答时, 用明确 reasonCode 区分失败原因, 供上层(chat)对用户可解释/可引导:
 * 禁止把"无法回答"笼统成一句话。reasonCode 由 Core 引擎确定性产出, 不依赖 LLM 文本。
 */
public final class StructuredFailureReason {

    private StructuredFailureReason() {
    }

    /** 范围歧义: 无法消解引用/实体集(需用户澄清范围) */
    public static final String AMBIGUOUS_SCOPE = "AMBIGUOUS_SCOPE";

    /** 字段不支持: 显式/继承字段未注册或数据源无可结构化数据 */
    public static final String UNSUPPORTED_FIELD = "UNSUPPORTED_FIELD";

    /** 指标未解析: 无注册指标/字段命中(可转逐实体语义执行) */
    public static final String MISSING_METRIC = "MISSING_METRIC";

    /** 结果集过期: 上一轮结果集已失效(知识/权限变化), 需重新查询 */
    public static final String STALE_RESULT_SET = "STALE_RESULT_SET";

    /** 权限变化: 引用的文档/知识库当前不可见 */
    public static final String PERMISSION_CHANGED = "PERMISSION_CHANGED";

    /** 结果为空: 范围内无匹配数据(引用集合为空或数据源空集) */
    public static final String EMPTY_RESULT_SET = "EMPTY_RESULT_SET";

    /** 运算不支持: 指标不支持该聚合运算(SUM/AVG/MIN/MAX/COUNT) */
    public static final String UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION";

    /** 领域不匹配: 引用的结果集与当前知识库领域不一致 */
    public static final String DOMAIN_MISMATCH = "DOMAIN_MISMATCH";

    /** 证据冲突: 同一字段存在多个当前值冲突, 禁止随意取值 */
    public static final String CONFLICT = "CONFLICT";

}
