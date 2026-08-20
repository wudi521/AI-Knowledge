package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 知识库槽位定义 Response VO")
@Data
public class AiKnowledgeBaseSlotRespVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "知识库编号", example = "1")
    private Long kbId;

    @Schema(description = "槽位编码(如 brand/faultType/purchaseTime)", example = "brand")
    private String slotCode;

    @Schema(description = "槽位名(如 品牌型号)", example = "品牌型号")
    private String slotName;

    @Schema(description = "抽取说明(喂给 LLM 的定义)", example = "从用户描述中提取产品品牌与型号")
    private String description;

    @Schema(description = "是否必填(1=缺则反问)", example = "true")
    private Boolean required;

    @Schema(description = "排序(组反问句顺序)", example = "1")
    private Integer sort;

    @Schema(description = "状态: 0=启用 1=禁用", example = "0")
    private Integer status;

    @Schema(description = "来源: LLM_AUTO(自动生成)/ MANUAL(手动或编辑过)", example = "MANUAL")
    private String source;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
