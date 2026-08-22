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

    /** 版本编号(版本状态机真实 versionId) */
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

    /** 向量(JSON 数组字符串, 发布时写 Milvus) */
    private String embedding;

    /** 父块编号 */
    private Long parentId;

    /** 业务键(版本内稳定唯一: (tenant_id, version_id, chunk_key) 唯一约束) */
    private String chunkKey;

    /** 版本内顺序(章节/文档中的序号) */
    private Integer chunkSeq;

    /** 角色: PARENT/CHILD/LEAF/TABLE/IMAGE */
    private String chunkRole;

    /** 章节路径(标题链, ">" 分隔) */
    private String sectionPath;

    /** 来源起始页(1-based; 未知 -1) */
    private Integer sourcePageStart;

    /** 来源结束页(未知 -1) */
    private Integer sourcePageEnd;

    /** token 数(估算口径 1.5 字符/token) */
    private Integer tokenCount;

    /** 内容 SHA-256(去重/向量复用) */
    private String contentHash;

}
