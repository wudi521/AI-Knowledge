package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

/**
 * 证据评估 RPC 响应: 证据冲突 DTO
 */
@Data
public class EvidenceConflictDTO {

    /** 证据 A 在 evidence 列表中的位置索引 */
    private Integer evidenceIndexA;

    /** 证据 B 在 evidence 列表中的位置索引 */
    private Integer evidenceIndexB;

    /** 矛盾原因说明 */
    private String reason;

}
