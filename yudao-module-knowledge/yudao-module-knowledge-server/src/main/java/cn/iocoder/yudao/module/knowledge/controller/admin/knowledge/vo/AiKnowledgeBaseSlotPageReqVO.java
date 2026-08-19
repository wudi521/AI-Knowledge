package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 知识库槽位定义分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiKnowledgeBaseSlotPageReqVO extends PageParam {

    @Schema(description = "知识库编号", example = "1")
    private Long kbId;

    @Schema(description = "槽位编码", example = "brand")
    private String slotCode;

    @Schema(description = "状态: 0=启用 1=禁用", example = "0")
    private Integer status;

}
