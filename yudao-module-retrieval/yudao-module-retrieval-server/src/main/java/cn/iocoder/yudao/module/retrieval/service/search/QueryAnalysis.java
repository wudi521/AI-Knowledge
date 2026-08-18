package cn.iocoder.yudao.module.retrieval.service.search;

import lombok.Data;

import java.util.List;

/**
 * 查询分析结果(LLM 输出)
 */
@Data
public class QueryAnalysis {

    /** 意图: WARRANTY/REFUND/LOGISTICS/REPAIR/PRICE/OTHER */
    private String intent;

    /** 关键实体 */
    private List<String> entities;

    /** 涉及的产品/品牌(如 苹果13/X100 Pro; 品牌一致性校验用, 无则空) */
    private List<String> products;

    /** 改写变体(不含原句) */
    private List<String> rewrites;

    /** 子问题(复杂问题拆解) */
    private List<String> subQuestions;

    /** 分析是否成功(失败时调用方仅用原句检索) */
    private boolean success;

}
