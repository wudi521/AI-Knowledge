package cn.iocoder.yudao.module.eval.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 评测平台 错误码枚举
 * 错误码分段: 1_012_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode EVAL_NOT_EXISTS = new ErrorCode(1_012_000_001, "评测平台数据不存在");

    ErrorCode EVAL_CASE_NOT_EXISTS = new ErrorCode(1_012_000_002, "评测用例不存在");

    ErrorCode EVAL_TASK_NOT_EXISTS = new ErrorCode(1_012_000_003, "评测任务不存在");

    ErrorCode EVAL_TASK_NO_CASE = new ErrorCode(1_012_000_004, "评测任务无可执行用例");

    ErrorCode EVAL_TASK_RUNNING = new ErrorCode(1_012_000_005, "该知识库已有评测任务运行中, 请等待完成后再发起");

}
