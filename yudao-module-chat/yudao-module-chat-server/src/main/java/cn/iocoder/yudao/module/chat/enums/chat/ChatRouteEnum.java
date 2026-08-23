package cn.iocoder.yudao.module.chat.enums.chat;

/**
 * 问答主链路由类型(Query Planner 权威产出, 全链透传)
 * <p>
 * 每个用户问题必须落到一个明确路由且不可为 null:
 * RULE / EXACT_METADATA / EXACT_CLAIM / SCOPED_RAG / HYBRID_RAG / ABSTAIN。
 * 地域/产品/文档等 Scope 类型属于 Trace/Analysis Metadata, 不单独作为新路由。
 */
public final class ChatRouteEnum {

    /** 硬规则命中(确定性规则, 如 跨省→3天) */
    public static final String RULE = "RULE";

    /** 结构化元数据精确查询(申请号/公布号/标题/权利要求数量等) */
    public static final String EXACT_METADATA = "EXACT_METADATA";

    /** 权利要求精确查询(原文/依赖/摘要) */
    public static final String EXACT_CLAIM = "EXACT_CLAIM";

    /** 单文档范围语义检索 */
    public static final String SCOPED_RAG = "SCOPED_RAG";

    /** 跨文档混合检索(BM25 + 向量融合) */
    public static final String HYBRID_RAG = "HYBRID_RAG";

    /** 证据不足/不可作答/异常兜底, 明确放弃回答 */
    public static final String ABSTAIN = "ABSTAIN";

    private ChatRouteEnum() {
    }

}
