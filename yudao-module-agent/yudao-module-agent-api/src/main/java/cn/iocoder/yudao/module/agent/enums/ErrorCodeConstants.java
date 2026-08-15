package cn.iocoder.yudao.module.agent.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * Agent编排 错误码枚举
 * 错误码分段: 1_009_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode AGENT_NOT_EXISTS = new ErrorCode(1_009_000_001, "Agent编排数据不存在");

}
