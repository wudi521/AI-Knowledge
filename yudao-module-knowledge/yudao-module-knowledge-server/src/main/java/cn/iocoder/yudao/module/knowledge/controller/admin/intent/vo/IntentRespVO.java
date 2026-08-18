package cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - AI 意图 Response VO")
@Data
public class IntentRespVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "知识库编号", example = "1")
    private Long kbId;

    @Schema(description = "意图名", example = "保修")
    private String name;

    @Schema(description = "意图说明(LLM总结或手填, 供分类参考)", example = "保修政策咨询")
    private String description;

    @Schema(description = "来源: LLM_AUTO/MANUAL", example = "MANUAL")
    private String source;

    @Schema(description = "状态", example = "0")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
