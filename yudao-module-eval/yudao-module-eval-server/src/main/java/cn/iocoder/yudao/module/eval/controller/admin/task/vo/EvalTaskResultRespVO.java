package cn.iocoder.yudao.module.eval.controller.admin.task.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 评测任务逐题结果 Response VO")
@Data
public class EvalTaskResultRespVO {

    @Schema(description = "考题编号", example = "1")
    private Long caseId;

    @Schema(description = "问题", example = "X100 Pro 碎屏能免费修吗")
    private String question;

    @Schema(description = "是否可作答", example = "true")
    private Boolean answerable;

    @Schema(description = "充分度", example = "0.9")
    private Double confidence;

    @Schema(description = "Recall@5", example = "1.0")
    private Double recallAt5;

    @Schema(description = "MRR", example = "1.0")
    private Double mrr;

    @Schema(description = "NDCG@5", example = "1.0")
    private Double ndcg;

    @Schema(description = "忠实度", example = "1.0")
    private Double faithfulness;

    @Schema(description = "幻觉率", example = "0.0")
    private Double hallucinationRate;

    @Schema(description = "引用准确率", example = "1.0")
    private Double citationAccuracy;

    @Schema(description = "是否达标", example = "true")
    private Boolean passed;

    @Schema(description = "未达标原因")
    private String failReasons;

    @Schema(description = "模型回答")
    private String answer;

    @Schema(description = "评估链路追踪号", example = "e8f3a2")
    private String traceId;

}
