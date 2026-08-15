package cn.iocoder.yudao.module.workflow.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 业务流程 错误码枚举
 * 错误码分段: 1_010_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode WORKFLOW_NOT_EXISTS = new ErrorCode(1_010_000_001, "业务流程数据不存在");

}
