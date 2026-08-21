package cn.iocoder.yudao.module.rule.controller.admin.rule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Schema(description = "管理后台 - AI 硬规则 试运行 Request VO")
@Data
public class AiRuleValidateReqVO {

    @Schema(description = "规则编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "规则编号不能为空")
    private Long id;

    @Schema(description = "事实(Map; 规则条件用 $f[\"key\"] 读取)", example = "{\"region\":\"跨省\"}")
    private Map<String, Object> facts;

}
