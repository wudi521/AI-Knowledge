package cn.iocoder.yudao.module.evidence.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 证据(领域模型: 由检索结果组装而来, 供去重/冲突/充分性/Claim 验证等后续环节使用)
 * <p>
 * 注意: score 为批次内 min-max 归一化后的得分(0~1), 供排序与充分性判定使用, 非原始检索分。
 */
@Data
@Builder
public class Evidence {

    /** 片段编号 */
    private Long chunkId;

    /** 片段内容(去重/冲突判定的比对源) */
    private String content;

    /** 来源文档编号(字符串: 兼容跨系统文档主键) */
    private String documentId;

    /** 来源文档名 */
    private String documentName;

    /** 版本号: V1/V2/... */
    private String versionNo;

    /** 版本编号(片段所属版本) */
    private Long versionId;

    /** 归一化得分(0~1, 批次内 min-max, 供排序用) */
    private Double score;

    /** 原始检索分(重排分优先, 否则 RRF 分; 未归一化, 供充分性判定区分度; 可为 null) */
    private Double rawScore;

    /**
     * 证据涉及的产品/品牌
     * <p>
     * 设计决策: 检索 RPC(RetrievalResultDTO)不暴露逐条证据的产品归属, 故当前恒为空列表;
     * Task 4 实体覆盖率判定将退化为 "questionProducts × 证据内容包含" 检查。
     */
    private List<String> products;

    /** 命中通道: ["bm25"] / ["vector"] / ["bm25","vector"] */
    private List<String> channels;

    /** 片段元数据(JSON; 专利来源卡片) */
    private String chunkMetadata;

}
