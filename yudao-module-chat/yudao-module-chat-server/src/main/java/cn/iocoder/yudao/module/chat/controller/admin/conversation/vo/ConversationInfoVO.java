package cn.iocoder.yudao.module.chat.controller.admin.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 会话信息 Response VO")
@Data
public class ConversationInfoVO {

    @Schema(description = "会话编号")
    private Long id;

    @Schema(description = "渠道(默认 WEB)")
    private String channel;

    @Schema(description = "客户标识(默认 anonymous)")
    private String customerId;

    @Schema(description = "绑定知识库编号")
    private Long kbId;

    @Schema(description = "知识领域")
    private String domainCode;

    @Schema(description = "会话所属用户编号")
    private Long userId;

    @Schema(description = "状态: ACTIVE 进行中 / TRANSFERRED 待人工接单 / CLOSED 已关闭")
    private String status;

    @Schema(description = "会话意图")
    private String intent;

    @Schema(description = "会话摘要(转人工时记录)")
    private String summary;

    @Schema(description = "转人工原因")
    private String transferReason;

    @Schema(description = "接单客服编号(人工接单后记录)")
    private Long operatorId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
