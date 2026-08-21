package cn.iocoder.yudao.module.rule.controller.admin.rule.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - AI 硬规则 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiRulePageReqVO extends PageParam {

    @Schema(description = "业务键", example = "delivery-condition")
    private String ruleKey;

    @Schema(description = "状态: 0=停用 1=启用(全量) 2=灰度中", example = "0")
    private Integer status;

}
