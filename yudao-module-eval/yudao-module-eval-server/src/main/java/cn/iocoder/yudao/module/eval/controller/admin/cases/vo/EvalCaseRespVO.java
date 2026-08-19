package cn.iocoder.yudao.module.eval.controller.admin.cases.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 评测用例 Response VO")
@Data
public class EvalCaseRespVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "问题", example = "X100 Pro 碎屏能免费修吗")
    private String question;

    @Schema(description = "标准答案", example = "不能, 碎屏属意外损坏")
    private String goldAnswer;

    @Schema(description = "标准证据(chunk 编号列表)", example = "[2101, 2093]")
    private List<Long> goldChunks;

    /** 中间字段: BeanUtils 按同名拷贝原始 JSON, 随后由 Controller 解析到 {@link #goldChunks}, 不对外暴露 */
    @JsonIgnore
    @Schema(hidden = true)
    private String goldChunksJson;

    @Schema(description = "来源反馈编号", example = "66")
    private Long sourceFeedback;

    @Schema(description = "知识库编号", example = "1")
    private Long kbId;

    @Schema(description = "分类", example = "综合")
    private String category;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
