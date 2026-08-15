package cn.iocoder.yudao.module.chat.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 对话工作台 错误码枚举
 * 错误码分段: 1_003_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode CHAT_NOT_EXISTS = new ErrorCode(1_003_000_001, "对话工作台数据不存在");

}
