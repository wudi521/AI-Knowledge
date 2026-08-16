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

}
