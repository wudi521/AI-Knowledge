package cn.iocoder.yudao.module.rule.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 规则引擎 错误码枚举
 * 错误码分段: 1_008_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode RULE_NOT_EXISTS = new ErrorCode(1_008_000_001, "规则引擎数据不存在");

    ErrorCode AI_RULE_NOT_EXISTS = new ErrorCode(1_008_000_002, "规则配置不存在");

    ErrorCode AI_RULE_NOT_EDITABLE = new ErrorCode(1_008_000_003, "仅可编辑停用版本");

    ErrorCode AI_RULE_GRAY_NEED_ENABLED = new ErrorCode(1_008_000_004, "该 key 无全量启用版本, 不能灰度");

    ErrorCode AI_RULE_COMPILE_FAILED = new ErrorCode(1_008_000_005, "DRL 编译失败: {}");

}
