package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 实体定义(Platform Core 只通过 domainCode + entityCode 读取, 不感知具体业务)。
 */
@Data
@Builder
public class EntityDefinition {

    /** 实体编码(如 PATENT_DOCUMENT / CLAIM) */
    private String entityCode;

    /** 领域编码(如 PATENT) */
    private String domainCode;

    /** 展示名(如 专利文献 / 权利要求) */
    private String displayLabel;

    /** 量词(如 件 / 项 / 篇) */
    private String classifier;

    /** 实体同义词(如 PATENT_DOCUMENT: 专利 / 文献) */
    private List<String> aliases;

}
