package cn.iocoder.yudao.module.model.api.dto;

import lombok.Data;

/**
 * 模型对话请求 DTO
 */
@Data
public class ModelChatReqDTO {

    /** 系统提示词 */
    private String system;

    /** 用户提示词 */
    private String user;

    /** 采样温度(结构化抽取/判定可置 0 保证确定性; null = 服务端默认 0.2) */
    private Double temperature;

    /** 图片列表(base64 data URL 或 http(s) URL; 非空时走 image 类型视觉模型, OpenAI 兼容 content 数组) */
    private java.util.List<String> images;

    /** 场景标识(路由用; null=默认场景) */
    private String scenario;

    /** 链路追踪号(计量/快照关联) */
    private String traceId;

}
