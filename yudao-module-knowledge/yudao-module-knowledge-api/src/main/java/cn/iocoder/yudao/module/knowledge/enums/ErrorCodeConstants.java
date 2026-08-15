package cn.iocoder.yudao.module.knowledge.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 知识平台 错误码枚举
 * 错误码分段: 1_004_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode KNOWLEDGE_NOT_EXISTS = new ErrorCode(1_004_000_001, "知识平台数据不存在");

}
