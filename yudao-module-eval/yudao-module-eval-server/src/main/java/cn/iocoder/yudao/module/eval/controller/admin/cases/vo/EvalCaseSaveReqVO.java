package cn.iocoder.yudao.module.eval.controller.admin.cases.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 评测用例 新增 Request VO")
@Data
public class EvalCaseSaveReqVO {

    @Schema(description = "问题", requiredMode = Schema.RequiredMode.REQUIRED, example = "X100 Pro 碎屏能免费修吗")
    @NotBlank(message = "问题不能为空")
    private String question;

    @Schema(description = "标准答案", example = "不能, 碎屏属意外损坏")
    private String goldAnswer;

    @Schema(description = "标准证据(chunk 编号列表)", example = "[2101, 2093]")
    private List<Long> goldChunks;

    @Schema(description = "知识库编号", example = "1")
    private Long kbId;

    @Schema(description = "分类", example = "综合")
    private String category;

}
