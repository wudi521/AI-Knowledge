package cn.iocoder.yudao.module.retrieval.service.search;

import lombok.Data;

import java.util.List;

/**
 * 查询分析结果(LLM 输出 + 领域确定性预解析结果)
 */
@Data
public class QueryAnalysis {

    /** 意图 */
    private String intent;

    /** 关键实体 */
    private List<String> entities;

    /** 涉及的产品/品牌 */
    private List<String> products;

    /** 地域 slot: 省份 */
    private String province;

    /** 地域 slot: 城市 */
    private String city;

    /** 改写变体(不含原句) */
    private List<String> rewrites;

    /** 子问题 */
    private List<String> subQuestions;

    /** 分析是否成功 */
    private boolean success;

    // ========== 专利领域确定性字段 ==========

    /** 申请号，例如 202311042981.1 */
    private String applicationNo;

    /** 公布号，例如 CN 122604134 A */
    private String publicationNo;

    /** 单项权利要求号 */
    private Integer claimNo;

    /** 多项/范围权利要求号 */
    private List<Integer> claimNos;

    /** 精确著录字段目标: CLAIM_COUNT/TITLE/APPLICANTS/... */
    private List<String> metadataFields;

    /** 权利要求问题子类型: RAW / DEPENDENCY / SUMMARY(EXACT_CLAIM 时) */
    private String claimQueryType;

    /** 检索路由建议: EXACT_METADATA / EXACT_CLAIM / SCOPED_RAG / HYBRID_RAG / ABSTAIN */
    private String route;
}
