package cn.iocoder.yudao.module.ingestion.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 专利权利要求精确定位请求。
 * documentIds 必须由上游在租户/知识库权限范围内先解析完成。
 */
@Data
public class PatentClaimLookupReqDTO {

    /** 已完成权限裁剪后的专利文档编号。 */
    private List<Long> documentIds;

    /** 权利要求号。 */
    private Integer claimNo;
}
