package cn.iocoder.yudao.module.model.controller.admin.prompt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - AI Prompt 业务键汇总 Response VO")
@Data
public class AiPromptKeyInfoRespVO {

    @Schema(description = "业务键", example = "query-analysis")
    private String promptKey;

    @Schema(description = "名称(最新版本)", example = "查询分析提示词")
    private String name;

    @Schema(description = "全量启用版本(无则为 null)", example = "1")
    private Integer enabledVersion;

    @Schema(description = "灰度版本(无则为 null)", example = "2")
    private Integer grayVersion;

    @Schema(description = "灰度租户列表")
    private List<Long> grayTenantIds;

    @Schema(description = "版本数", example = "3")
    private Integer versionCount;

}
