package cn.iocoder.yudao.module.model.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 文本向量化请求 DTO(带场景/追踪号; 原 embedding(List<String>) 保留兼容)
 */
@Data
public class ModelEmbeddingReqDTO {

    /** 文本列表(批量) */
    private List<String> texts;

    /** 场景标识(路由用; null=默认场景) */
    private String scenario;

    /** 链路追踪号 */
    private String traceId;

}
