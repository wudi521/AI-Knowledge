package cn.iocoder.yudao.module.model.controller.admin.prompt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - AI Prompt Response VO")
@Data
public class AiPromptRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "业务键", example = "query-analysis")
    private String promptKey;

    @Schema(description = "名称", example = "查询分析提示词")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "提示词内容")
    private String content;

    @Schema(description = "版本号(同 key 自增)", example = "1")
    private Integer version;

    @Schema(description = "状态: 0=停用 1=启用(全量) 2=灰度中", example = "0")
    private Integer status;

    @Schema(description = "灰度租户列表")
    private List<Long> grayTenantIds;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
