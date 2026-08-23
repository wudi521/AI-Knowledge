package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 证据评估 RPC 响应: 单条证据项 DTO(统一 Evidence 契约, 商用化)
 * <p>
 * 该 DTO 面向业务展示, 禁止携带 Vector ID / Milvus PK / 原始 BM25 分 / Embedding 等内部字段。
 * score 为批次内 min-max 归一化融合置信度(0~1), 非原始检索分。
 */
@Data
public class EvidenceItemDTO {

    /** 证据编号(稳定唯一: 本批证据列表内去重后 chunkId) */
    private Long evidenceId;

    /** 片段编号 */
    private Long chunkId;

    /** 来源文档编号 */
    private Long documentId;

    /** 来源文档名 */
    private String documentName;

    /** 版本编号(片段所属版本; 历史快照回看用) */
    private Long versionId;

    /** 版本号: V1/V2/... */
    private String versionNo;

    /** 知识库编号 */
    private Long kbId;

    /** 知识领域编码(如 PATENT) */
    private String domainCode;

    /** 片段类型(专利: 权利要求书/说明书/著录信息 等) */
    private String sectionType;

    /** 片段小节标题(如 权利要求 1 / 发明内容) */
    private String sectionTitle;

    /** 权利要求编号(权利要求片段) */
    private String claimNo;

    /** 起始页码 */
    private Integer pageStart;

    /** 结束页码 */
    private Integer pageEnd;

    /** 申请号 */
    private String applicationNo;

    /** 公布号 */
    private String publicationNo;

    /** 片段内容 */
    private String content;

    /** 归一化得分(0~1, 批次内 min-max) */
    private Double score;

    /** 命中通道: ["bm25"] / ["vector"] / ["bm25","vector"](内部诊断, 不对外展示) */
    private List<String> channels;

    /** 片段元数据(JSON; 内部字段, 不对外展示) */
    private String chunkMetadata;

}
