package cn.iocoder.yudao.module.model.controller.admin.prompt.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - AI Prompt 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiPromptPageReqVO extends PageParam {

    @Schema(description = "业务键", example = "query-analysis")
    private String promptKey;

    @Schema(description = "状态: 0=停用 1=启用(全量) 2=灰度中", example = "0")
    private Integer status;

}
