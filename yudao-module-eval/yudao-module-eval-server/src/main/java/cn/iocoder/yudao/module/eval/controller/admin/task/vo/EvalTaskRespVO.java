package cn.iocoder.yudao.module.eval.controller.admin.task.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 评测任务 Response VO")
@Data
public class EvalTaskRespVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "状态: RUNNING / DONE / FAILED", example = "DONE")
    private String status;

    @Schema(description = "评测知识库(为空 = 全部用例)", example = "1")
    private Long kbId;

    @Schema(description = "考题数", example = "2")
    private Integer caseCount;

    @Schema(description = "模型", example = "evidence-v1")
    private String model;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "闸门是否通过(0/1; DONE 且全题达标为 1)", example = "1")
    private Integer gatePass;

    @Schema(description = "指标快照(均值等, 从 metrics JSON 解析)", example = "{\"recallAt5\":0.9}")
    private Map<String, Object> metrics;

    @Schema(description = "失败用例明细(从 fail_cases JSON 解析)", example = "[{\"caseId\":1,\"failReasons\":\"Recall@5 0.5<0.9\"}]")
    private List<Map<String, Object>> failCases;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
