package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 证据评估 RPC 响应: 单条证据项 DTO
 */
@Data
public class EvidenceItemDTO {

    /** 片段编号 */
    private Long chunkId;

    /** 片段内容 */
    private String content;

    /** 来源文档名 */
    private String documentName;

    /** 版本号: V1/V2/... */
    private String versionNo;

    /** 归一化得分(0~1, 批次内 min-max) */
    private Double score;

    /** 命中通道: ["bm25"] / ["vector"] / ["bm25","vector"] */
    private List<String> channels;

    /** 片段元数据(JSON; 专利来源卡片) */
    private String chunkMetadata;

}
