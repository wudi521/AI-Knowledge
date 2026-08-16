package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

/**
 * 版本 RPC DTO(片段页联表用: versionId -> docId/versionNo)
 */
@Data
public class KnowledgeVersionRespDTO {

    /** 版本编号 */
    private Long id;

    /** 文档编号 */
    private Long docId;

    /** 版本号: V1/V2/... */
    private String versionNo;

    /** 状态: DRAFT/REVIEW/PUBLISHED/EXPIRED/ARCHIVED */
    private String status;

}
