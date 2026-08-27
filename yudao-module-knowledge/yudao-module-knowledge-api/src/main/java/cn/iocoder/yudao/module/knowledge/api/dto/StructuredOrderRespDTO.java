package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;

/** 权威结构化排序结果及完整性证明。 */
@Data
public class StructuredOrderRespDTO {
    /** 按请求 typed order 排好序的代表 documentId。 */
    private List<Long> documentIds;
    /** 当前授权/请求 scope 内参与比较的逻辑实体总数。 */
    private Long sourceEntityCount;
    /** 参与排序所必需字段缺失的逻辑实体数；必须为 0 才能形成全局结论。 */
    private Long missingValueCount;
    /** 同一逻辑实体在排序字段上存在冲突的实体数；必须为 0 才能形成全局结论。 */
    private Long conflictCount;
    /** 后端是否证明扫描/比较覆盖完整 scope。 */
    private boolean completeDataset;
}
