package cn.iocoder.yudao.module.ingestion.trace;

import lombok.Builder;
import lombok.Data;

/**
 * 入库链路上下文(Knowledge Ops: 一条知识从文件到索引的完整链路标识)
 */
@Data
@Builder
public class TraceContext {

    /** 链路追踪号(入库任务/文档级) */
    private String traceId;

    /** 知识库编号 */
    private Long kbId;

    /** 文档编号 */
    private Long documentId;

    /** 版本编号 */
    private Long versionId;

    /** 入库任务编号(ai_ingestion_job.id) */
    private Long jobId;

    /** 领域代码(GENERAL/PATENT) */
    private String domainCode;

    /** 租户编号 */
    private Long tenantId;
}
