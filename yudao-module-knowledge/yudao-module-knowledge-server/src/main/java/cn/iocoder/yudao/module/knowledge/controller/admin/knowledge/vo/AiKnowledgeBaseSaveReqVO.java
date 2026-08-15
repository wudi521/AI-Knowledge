package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Schema(description = "管理后台 - 知识库 新增/修改 Request VO")
@Data
public class AiKnowledgeBaseSaveReqVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "产品与售后知识库")
    @NotEmpty(message = "名称不能为空")
    private String name;

    @Schema(description = "切分策略", example = "ParentChild")
    private String chunkStrategy;

    @Schema(description = "Embedding 模型", example = "BGE-M3")
    private String embedModel;

    @Schema(description = "状态", example = "1")
    private Integer status;

    @Schema(description = "备注", example = "核心知识库")
    private String remark;

}
