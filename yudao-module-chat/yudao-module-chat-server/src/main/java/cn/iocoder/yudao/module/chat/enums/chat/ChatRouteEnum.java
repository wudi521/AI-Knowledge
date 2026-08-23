package cn.iocoder.yudao.module.chat.enums.chat;

/**
 * 问答主链路由类型(P0-04 收口)
 * <p>
 * 每个用户问题必须落到一个明确路由; EXACT_METADATA / EXACT_CLAIM 的确定性判定
 * 依赖 P0-05 / P0-06 的专利元数据与权利要求查找能力, 本轮先收敛到
 * SCOPED_RAG(单文档聚焦) / HYBRID_RAG(跨文档混合检索) / ABSTAIN(放弃作答)。
 */
public final class ChatRouteEnum {

    /** 结构化元数据精确查询(申请号/公布号/标题等; P0-05 补齐) */
    public static final String EXACT_METADATA = "EXACT_METADATA";

    /** 权利要求精确查询(原文/依赖/摘要; P0-06 补齐) */
    public static final String EXACT_CLAIM = "EXACT_CLAIM";

    /** 单文档范围语义检索 */
    public static final String SCOPED_RAG = "SCOPED_RAG";

    /** 跨文档混合检索(BM25 + 向量融合) */
    public static final String HYBRID_RAG = "HYBRID_RAG";

    /** 证据不足/不可作答, 明确放弃回答 */
    public static final String ABSTAIN = "ABSTAIN";

    private ChatRouteEnum() {
    }

}
