package cn.iocoder.yudao.module.eval.controller.admin.task.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 评测任务 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class EvalTaskPageReqVO extends PageParam {

    @Schema(description = "状态", example = "DONE")
    private String status;

    @Schema(description = "知识库编号", example = "1")
    private Long kbId;

}
