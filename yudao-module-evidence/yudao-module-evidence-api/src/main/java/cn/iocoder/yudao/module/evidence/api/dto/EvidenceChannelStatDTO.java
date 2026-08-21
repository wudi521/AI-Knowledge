package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

/**
 * 通道召回统计 DTO(BM25/向量/融合数量; 透传检索结果, 供前端检索诊断)
 */
@Data
public class EvidenceChannelStatDTO {

    /** BM25 通道召回数 */
    private Integer bm25;

    /** 向量通道召回数 */
    private Integer vector;

    /** RRF 融合数 */
    private Integer fused;

}
