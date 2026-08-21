package cn.iocoder.yudao.module.rule.controller.admin.rule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - AI 硬规则 Response VO")
@Data
public class AiRuleRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "业务键", example = "delivery-condition")
    private String ruleKey;

    @Schema(description = "名称", example = "跨省配送时效规则")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "DRL 规则文本")
    private String drlContent;

    @Schema(description = "版本号(同 key 自增)", example = "1")
    private Integer version;

    @Schema(description = "状态: 0=停用 1=启用(全量) 2=灰度中", example = "0")
    private Integer status;

    @Schema(description = "灰度租户列表")
    private List<Long> grayTenantIds;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
