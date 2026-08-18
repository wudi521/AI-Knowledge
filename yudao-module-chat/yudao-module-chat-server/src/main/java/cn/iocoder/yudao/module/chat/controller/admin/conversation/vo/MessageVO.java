package cn.iocoder.yudao.module.chat.controller.admin.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 会话消息 Response VO")
@Data
public class MessageVO {

    @Schema(description = "消息编号")
    private Long id;

    @Schema(description = "角色: USER 用户 / AI 机器人 / SYSTEM 系统")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "引用证据 chunkId 列表(JSON 字符串解析, 为空返回空列表)")
    private List<String> citations;

    @Schema(description = "意图(USER 消息识别结果)")
    private String intent;

    @Schema(description = "置信度(0~1, 4 位小数, AI 消息)")
    private BigDecimal confidence;

    @Schema(description = "证据链路追踪号(证据评估 traceId, AI 消息)")
    private String traceId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
