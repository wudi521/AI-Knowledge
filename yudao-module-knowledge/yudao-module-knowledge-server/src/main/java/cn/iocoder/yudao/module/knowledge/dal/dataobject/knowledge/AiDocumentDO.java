package cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * AI 文档 DO
 */
@TableName("ai_document")
@KeySequence("ai_document_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDocumentDO extends TenantBaseDO {

    /** 编号 */
    @TableId
    private Long id;
    /** 知识库编号 */
    private Long kbId;
    /** 文档名 */
    private String name;
    /** 类型: PDF/WORD/EXCEL/PPT/IMAGE/MD */
    private String type;
    /** 存储路径(MinIO) */
    private String storagePath;
    /** 文件 SHA-256 */
    private String fileHash;
    /** 解析状态: PENDING/PARSING/EMBEDDING/INDEXED/FAILED */
    private String parseStatus;
    /** 失败原因 */
    private String errorMsg;

    /** 切分片段数(解析结果) */
    private Integer chunkCount;
    /** 上传人 */
    private String owner;

    /** 当前版本号(联表, 非表字段) */
    @TableField(exist = false)
    private String versionNo;

    /** 当前版本状态(联表, 非表字段) */
    @TableField(exist = false)
    private String versionStatus;

}
