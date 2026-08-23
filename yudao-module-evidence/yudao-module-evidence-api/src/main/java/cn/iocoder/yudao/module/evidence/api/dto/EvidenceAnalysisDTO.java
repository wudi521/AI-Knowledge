package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 语义分析详情 DTO(意图/实体/改写/子问题; 透传检索结果, 供前端检索诊断)
 */
@Data
public class EvidenceAnalysisDTO {

    /** 意图分类(固定枚举或知识库意图名) */
    private String intent;

    /** 关键实体 */
    private List<String> entities;

    /** 改写变体 */
    private List<String> rewrites;

    /** 子问题 */
    private List<String> subQuestions;

    /** 分析是否成功(失败时走关键词检索) */
    private Boolean success;

    /** 检索路由(Query Planner 权威产出: EXACT_METADATA/EXACT_CLAIM/SCOPED_RAG/HYBRID_RAG/ABSTAIN) */
    private String route;

}
