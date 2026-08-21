package cn.iocoder.yudao.module.evidence.service.assemble;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 组装结果(证据组装器的输出, 供冲突判定/充分性判定/Claim 验证等后续环节使用)
 */
@Data
@AllArgsConstructor
public class AssembledEvidence {

    /** 组装后的证据(按得分降序) */
    private List<Evidence> evidences;

    /** 问题涉及的产品/品牌(透传检索分析结果, 供充分性判定使用) */
    private List<String> questionProducts;

    /** 产品/品牌一致性门禁: true = 拒绝作答(透传检索结果) */
    private Boolean answerBlocked;

    /** 拒绝作答原因 */
    private String answerReason;

    /** 语义分析详情(意图/实体/改写/子问题; 透传检索结果, 供评估响应透传给前端诊断) */
    private RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis;

    /** 通道召回统计(BM25/向量/融合; 供前端检索诊断) */
    private RetrievalSearchRespDTO.RetrievalChannelStatDTO channels;

    /**
     * 空结果(检索 RPC 失败/异常时优雅降级返回, 不抛异常)
     */
    public static AssembledEvidence empty() {
        return new AssembledEvidence(Collections.emptyList(), Collections.emptyList(), null, null, null, null);
    }

}
