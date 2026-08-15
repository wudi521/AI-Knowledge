package cn.iocoder.yudao.module.eval.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 评测平台 错误码枚举
 * 错误码分段: 1_012_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode EVAL_NOT_EXISTS = new ErrorCode(1_012_000_001, "评测平台数据不存在");

}
