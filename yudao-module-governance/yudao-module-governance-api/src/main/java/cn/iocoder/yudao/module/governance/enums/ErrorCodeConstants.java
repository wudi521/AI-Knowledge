package cn.iocoder.yudao.module.governance.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 治理平台 错误码枚举
 * 错误码分段: 1_013_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode GOVERNANCE_NOT_EXISTS = new ErrorCode(1_013_000_001, "治理平台数据不存在");

}
