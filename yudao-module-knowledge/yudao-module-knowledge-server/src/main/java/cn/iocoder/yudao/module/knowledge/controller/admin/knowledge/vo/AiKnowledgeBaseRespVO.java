package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 知识库 Response VO")
@Data
public class AiKnowledgeBaseRespVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "名称", example = "产品与售后知识库")
    private String name;

    @Schema(description = "知识领域: GENERAL/PATENT", example = "GENERAL")
    private String domainCode;

    @Schema(description = "状态", example = "1")
    private Integer status;

    @Schema(description = "备注", example = "核心知识库")
    private String remark;

    @Schema(description = "可见角色 code, 逗号分隔; 空=全部可见")
    private String visibleRoles;

    @Schema(description = "有效期至(空=永久)")
    private LocalDateTime effectiveTo;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
