package cn.iocoder.yudao.module.retrieval.controller.admin.search.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 检索请求 VO
 */
@Data
public class RetrievalReqVO {

    @NotEmpty(message = "检索内容不能为空")
    private String query;

    /** 限定知识库编号列表(空 = 全部可见知识库) */
    private List<Long> kbIds;

    /** 返回条数(默认 5, 最大 20) */
    private Integer topK;

}
