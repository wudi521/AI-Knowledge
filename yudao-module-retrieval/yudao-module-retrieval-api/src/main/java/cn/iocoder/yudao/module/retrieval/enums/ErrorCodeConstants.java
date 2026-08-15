package cn.iocoder.yudao.module.retrieval.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 检索平台 错误码枚举
 * 错误码分段: 1_006_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode RETRIEVAL_NOT_EXISTS = new ErrorCode(1_006_000_001, "检索平台数据不存在");

}
