package cn.iocoder.yudao.module.evidence.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 证据平台 错误码枚举
 * 错误码分段: 1_007_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode EVIDENCE_NOT_EXISTS = new ErrorCode(1_007_000_001, "证据平台数据不存在");

}
