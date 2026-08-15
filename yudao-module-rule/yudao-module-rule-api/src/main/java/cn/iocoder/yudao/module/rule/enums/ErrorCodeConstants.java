package cn.iocoder.yudao.module.rule.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 规则引擎 错误码枚举
 * 错误码分段: 1_008_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode RULE_NOT_EXISTS = new ErrorCode(1_008_000_001, "规则引擎数据不存在");

}
