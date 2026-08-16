package cn.iocoder.yudao.module.knowledge.controller.admin.review.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 审核条目分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ReviewItemPageReqVO extends PageParam {

    @Schema(description = "文档编号")
    private Long docId;

    @Schema(description = "版本编号")
    private Long versionId;

    @Schema(description = "条目状态: PENDING/APPROVED/REJECTED")
    private String status;

    @Schema(description = "条目类型: POLICY/PRICE/LEGAL/FAQ/SOP")
    private String itemType;

    @Schema(description = "风险等级: HIGH/MED/LOW")
    private String riskLevel;

}
