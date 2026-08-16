package cn.iocoder.yudao.module.ingestion.dal.dataobject;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 知识片段(与 ai_chunk 表对应, 租户隔离由 TenantBaseDO.tenantId 承载)
 */
@TableName("ai_chunk")
@Data
@EqualsAndHashCode(callSuper = true)
public class ChunkDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 版本编号(暂用文档 id 占位, 版本状态机后续接入) */
    private Long versionId;

    /** 片段内容 */
    private String content;

    /** 类型: SEMANTIC/TABLE/FAQ/POLICY */
    private String chunkType;

    /** 元数据 */
    private String metadata;

    /** 状态 */
    private String status;

    /** Milvus 向量关联键 */
    private String vectorKey;

    /** 父块编号 */
    private Long parentId;

}
