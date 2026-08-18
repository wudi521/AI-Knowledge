package cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 证据评估请求 VO
 */
@Data
public class EvidenceEvaluateReqVO {

    @NotEmpty(message = "评估问题不能为空")
    private String query;

    /** 限定知识库编号列表(空 = 全部可见知识库) */
    private List<Long> kbIds;

    /** 返回证据条数(默认 8) */
    private Integer topK = 8;

}
