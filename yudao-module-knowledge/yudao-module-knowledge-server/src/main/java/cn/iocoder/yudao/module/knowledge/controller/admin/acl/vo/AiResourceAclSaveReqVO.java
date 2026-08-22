package cn.iocoder.yudao.module.knowledge.controller.admin.acl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 资源 ACL 新增/修改 Request VO")
@Data
public class AiResourceAclSaveReqVO {

    private Long id;

    @Schema(description = "资源类型: KB/DOCUMENT/CHUNK/ENTITY", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "资源类型不能为空")
    private String resourceType;

    @Schema(description = "资源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "资源编号不能为空")
    private Long resourceId;

    @Schema(description = "主体类型: USER/ROLE/DEPT/ORG/ALL", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "主体类型不能为空")
    private String subjectType;

    @Schema(description = "主体编号(ALL 时为空)")
    private String subjectId;

    @Schema(description = "动作: READ/WRITE/REVIEW/PUBLISH/ADMIN")
    private String action = "READ";

    @Schema(description = "效果: ALLOW/DENY")
    private String effect = "ALLOW";

    @Schema(description = "是否继承父资源")
    private Boolean inherit = true;

    @Schema(description = "生效起始(空=永久)")
    private LocalDateTime effectiveFrom;

    @Schema(description = "生效截止(空=永久)")
    private LocalDateTime effectiveTo;

}
