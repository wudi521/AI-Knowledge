package cn.iocoder.yudao.module.eval.controller.admin.cases.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 评测用例 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class EvalCasePageReqVO extends PageParam {

    @Schema(description = "问题(模糊匹配)", example = "碎屏")
    private String question;

    @Schema(description = "知识库编号", example = "1")
    private Long kbId;

    @Schema(description = "分类", example = "综合")
    private String category;

}
