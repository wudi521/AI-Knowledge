package cn.iocoder.yudao.module.model.controller.admin.model.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 模型配置分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiModelConfigPageReqVO extends PageParam {

    @Schema(description = "名称", example = "BGE")
    private String name;

    @Schema(description = "类型", example = "embedding")
    private String type;

    @Schema(description = "场景标识")
    private String scenario;

    @Schema(description = "状态", example = "1")
    private Integer status;

}
