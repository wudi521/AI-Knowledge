package cn.iocoder.yudao.module.retrieval.api.dto;

import lombok.Data;

/**
 * 对话轮次 DTO(多轮上下文透传用)
 * <p>
 * 按 spec 与 evidence-api 的 ChatTurnDTO 重复定义 —— 跨模块 DTO 独立, 不共享类。
 */
@Data
public class ChatTurnDTO {

    /** 角色: USER / AI */
    private String role;

    /** 该轮内容 */
    private String content;

}
