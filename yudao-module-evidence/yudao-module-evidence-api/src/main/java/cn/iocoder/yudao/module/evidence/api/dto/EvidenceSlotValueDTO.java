package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

/**
 * 证据评估 RPC 响应: 槽位值项 DTO
 */
@Data
public class EvidenceSlotValueDTO {

    /** 槽位编码 */
    private String code;

    /** 槽位名 */
    private String name;

    /** 抽取到的原文(缺失项恒为 null) */
    private String value;

}
