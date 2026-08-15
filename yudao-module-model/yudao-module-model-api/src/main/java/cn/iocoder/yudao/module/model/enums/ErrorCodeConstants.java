package cn.iocoder.yudao.module.model.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 模型网关 错误码枚举
 * 错误码分段: 1_011_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode MODEL_CONFIG_NOT_EXISTS = new ErrorCode(1_111_001_000, "模型配置不存在");

    ErrorCode MODEL_NOT_EXISTS = new ErrorCode(1_011_000_001, "模型网关数据不存在");

}
