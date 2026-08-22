package cn.iocoder.yudao.module.retrieval.dal.dataobject.trace;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 检索追踪(F5: 审计/评测/可观测)
 */
@TableName("ai_retrieval_trace")
@Data
@EqualsAndHashCode(callSuper = true)
public class RetrievalTraceDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 链路追踪号 */
    private String traceId;

    /** 会话编号(Knowledge Ops 查询链路) */
    private Long conversationId;

    /** 查询 */
    private String query;

    /** 路由: HYBRID_RAG/SCOPE_FILTER_HYBRID_RAG/ABSTAIN */
    private String route;

    /** 领域代码(GENERAL/PATENT) */
    private String domainCode;

    /** 意图 */
    private String intent;

    /** 检索变体数 */
    private Integer variantCount;

    /** BM25 命中数 */
    private Integer bm25Hits;

    /** 向量命中数 */
    private Integer vectorHits;

    /** 融合候选数 */
    private Integer fused;

    /** 返回结果数 */
    private Integer resultCount;

    /** 耗时(ms) */
    private Integer elapsedMs;

    /** 是否阻断 */
    private Boolean blocked;

}
