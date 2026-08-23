package cn.iocoder.yudao.module.retrieval.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 检索结果 RPC DTO(单条证据)
 */
@Data
public class RetrievalResultDTO {

    /** 片段编号 */
    private Long chunkId;

    /** 片段内容 */
    private String content;

    /** 来源文档编号 */
    private Long documentId;

    /** 来源文档名 */
    private String documentName;

    /** 版本号: V1/V2/... */
    private String versionNo;

    /** 版本编号(片段所属版本) */
    private Long versionId;

    /** RRF 融合分 */
    private Double rrfScore;

    /** 重排分 */
    private Float rerankScore;

    /** 命中通道: ["bm25"] / ["vector"] / ["bm25","vector"] */
    private List<String> channels;

    /** 片段元数据(JSON; 专利: applicationNo/publicationNo/sectionType/claimNo/pageStart 等) */
    private String chunkMetadata;

}
