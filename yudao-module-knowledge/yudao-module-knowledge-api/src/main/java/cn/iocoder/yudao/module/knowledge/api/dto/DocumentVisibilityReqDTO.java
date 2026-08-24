package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 文档粒度可见性校验请求(CQ-38): 多轮结果集引用重校验用。
 * 返回每个文档当前用户是否可见 + 发布版本是否有效(不返回文档内容)。
 */
@Data
public class DocumentVisibilityReqDTO {

    /** 待校验文档编号列表 */
    private List<Long> documentIds;

    /** 当前用户编号 */
    private Long userId;

}
