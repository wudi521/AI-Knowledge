package cn.iocoder.yudao.module.rule.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 规则评估响应 DTO
 */
@Data
public class RuleEvaluateRespDTO {

    /** 是否命中(命中 → 调用方直接以规则结论作答, 不走原链路) */
    private Boolean matched;

    /** 命中结论列表(领域无关: code/text 全是数据) */
    private List<Conclusion> conclusions;

    /**
     * 规则结论(通用, 领域无关)
     */
    @Data
    public static class Conclusion {

        /** 结论编码(可空, 如 delivery-3d) */
        private String code;

        /** 结论文本(如 跨省配送时效 3 天) */
        private String text;

    }

}
