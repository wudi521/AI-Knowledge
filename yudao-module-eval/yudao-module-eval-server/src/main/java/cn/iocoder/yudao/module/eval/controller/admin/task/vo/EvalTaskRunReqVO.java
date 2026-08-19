package cn.iocoder.yudao.module.eval.controller.admin.task.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 发起评测任务 Request VO")
@Data
public class EvalTaskRunReqVO {

    @Schema(description = "选考题编号列表(选考题; 为空则按 kbId 或全部用例)", example = "[1,2]")
    private List<Long> caseIds;

    @Schema(description = "评测知识库编号(caseIds 为空时生效; 为空 = 全部用例)", example = "1")
    private Long kbId;

}
