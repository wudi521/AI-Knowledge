package cn.iocoder.yudao.module.model.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 重排请求 DTO
 */
@Data
public class ModelRerankReqDTO {

    /** 查询文本 */
    private String query;

    /** 候选文本列表 */
    private List<String> documents;

}
