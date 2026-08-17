package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Collection;

@Schema(description = "管理后台 - AI 文档分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiDocumentPageReqVO extends PageParam {

    @Schema(description = "知识库编号")
    private Long kbId;

    @Schema(description = "文档名", example = "售后")
    private String name;

    @Schema(description = "解析状态", example = "PENDING")
    private String parseStatus;

    /** 可见知识库编号集合(权限过滤用, 不来自前端) */
    private Collection<Long> kbIds;

}
