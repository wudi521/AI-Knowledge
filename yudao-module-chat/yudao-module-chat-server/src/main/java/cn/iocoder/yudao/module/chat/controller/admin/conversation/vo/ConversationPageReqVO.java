package cn.iocoder.yudao.module.chat.controller.admin.conversation.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 会话分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationPageReqVO extends PageParam {

    @Schema(description = "状态: ACTIVE 进行中 / TRANSFERRED 待人工接单 / CLOSED 已关闭", example = "TRANSFERRED")
    private String status;

}
