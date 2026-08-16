package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

@Data
public class KnowledgeDocumentRespDTO {

    /** 文档编号 */
    private Long id;

    /** 知识库编号 */
    private Long kbId;

    /** 文档名 */
    private String name;

    /** 类型: TXT/MD/PDF/WORD/EXCEL/PPT */
    private String type;

    /** 存储路径(MinIO URL) */
    private String storagePath;

    /** 解析状态 */
    private String parseStatus;

    /** 租户编号 */
    private Long tenantId;

}
