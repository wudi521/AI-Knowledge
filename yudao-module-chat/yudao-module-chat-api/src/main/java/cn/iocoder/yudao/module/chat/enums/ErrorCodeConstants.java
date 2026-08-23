package cn.iocoder.yudao.module.chat.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 对话工作台 错误码枚举
 * 错误码分段: 1_003_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode CHAT_NOT_EXISTS = new ErrorCode(1_003_000_001, "对话工作台数据不存在");
    ErrorCode CONVERSATION_NOT_EXISTS = new ErrorCode(1_003_000_002, "会话不存在");
    ErrorCode MESSAGE_NOT_EXISTS = new ErrorCode(1_003_000_003, "消息不存在");
    ErrorCode FEEDBACK_TYPE_ERROR = new ErrorCode(1_003_000_004, "反馈类型不正确(仅支持 THUMB_UP/THUMB_DOWN)");
    ErrorCode KNOWLEDGE_BASE_NOT_EXISTS = new ErrorCode(1_003_000_005, "知识库不存在或无权访问");
    ErrorCode CONVERSATION_CONTEXT_CONFLICT = new ErrorCode(1_003_000_006, "会话已绑定其他知识库，请新建会话");

}
