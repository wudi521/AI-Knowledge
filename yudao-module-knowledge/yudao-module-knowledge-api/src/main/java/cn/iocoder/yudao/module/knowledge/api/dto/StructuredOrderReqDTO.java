package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;

/** 受控结构化排序请求；不允许透传任意 SQL/列名/函数。 */
@Data
public class StructuredOrderReqDTO {
    private Long kbId;
    private String domainCode;
    private String fieldCode;
    private String transformCode;
    private String direction;
    private Integer limit;
    private Boolean publishedOnly;
    private List<Long> resolvedEntityIds;
}
