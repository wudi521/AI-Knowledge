package cn.iocoder.yudao.module.eval.controller.admin.cases.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 评测用例 更新 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class EvalCaseUpdateReqVO extends EvalCaseSaveReqVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "编号不能为空")
    private Long id;

}
