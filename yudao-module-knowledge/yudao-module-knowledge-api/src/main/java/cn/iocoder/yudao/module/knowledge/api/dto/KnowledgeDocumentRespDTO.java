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

    /** 切分策略(文档级: auto/structure/parent-child/semantic/policy/faq/table/image) */
    private String chunkStrategy;

    /** 切分策略参数(JSON, 可选) */
    private String chunkStrategyParams;

    /** 知识库领域代码(GENERAL/PATENT; 供 ingestion 领域适配器路由) */
    private String domainCode;

    /** 领域文档元数据(JSON; 专利著录信息等) */
    private String domainMetadata;

    /** 租户编号 */
    private Long tenantId;

    /** 涉及产品/品牌(逗号分隔) */
    private String products;

    /** 当前版本编号(管线写 chunk.version_id 用) */
    private Long currentVersionId;

}
