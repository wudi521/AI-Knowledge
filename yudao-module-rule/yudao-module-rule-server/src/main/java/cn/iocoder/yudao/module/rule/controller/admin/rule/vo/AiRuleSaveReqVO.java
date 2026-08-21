package cn.iocoder.yudao.module.rule.controller.admin.rule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "管理后台 - AI 硬规则 新增 Request VO")
@Data
public class AiRuleSaveReqVO {

    @Schema(description = "业务键(如 warranty-condition/delivery-condition)", requiredMode = Schema.RequiredMode.REQUIRED, example = "delivery-condition")
    @NotBlank(message = "业务键不能为空")
    private String ruleKey;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "跨省配送时效规则")
    @NotBlank(message = "名称不能为空")
    private String name;

    @Schema(description = "说明", example = "跨省配送硬性时效")
    private String description;

    @Schema(description = "DRL 规则文本(需 import java.util.Map 与 RuleResult; 结论 insert(new RuleResult(code, text)))", requiredMode = Schema.RequiredMode.REQUIRED, example = "package rules\nimport java.util.Map;\nimport cn.iocoder.yudao.module.rule.service.rule.RuleResult;\nrule \"跨省配送时效\"\nwhen\n  $f: Map($f[\"region\"] == \"跨省\")\nthen\n  insert(new RuleResult(\"delivery-3d\", \"跨省配送时效 3 天\"));\nend")
    @NotBlank(message = "DRL 规则文本不能为空")
    private String drlContent;

}
