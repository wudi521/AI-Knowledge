package cn.iocoder.yudao.module.ingestion.dal.dataobject;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 知识片段(与 ai_chunk 表对应)
 */
@TableName("ai_chunk")
@Data
@EqualsAndHashCode(callSuper = true)
public class ChunkDO extends BaseDO {

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
