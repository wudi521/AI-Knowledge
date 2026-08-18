package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

/**
 * 证据评估 RPC 响应: 逐句断言验证结果 DTO
 */
@Data
public class EvidenceClaimDTO {

    /** 断言句子原文 */
    private String text;

    /** 判定: SUPPORTED / UNSUPPORTED */
    private String verdict;

    /** 支撑证据在 evidence 列表中的位置索引(0 起; -1 = 无支撑) */
    private Integer evidenceIndex;

}
