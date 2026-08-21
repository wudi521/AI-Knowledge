package cn.iocoder.yudao.module.ingestion.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 入库管线 错误码枚举
 * 错误码分段: 1_005_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode INGESTION_NOT_EXISTS = new ErrorCode(1_005_000_001, "入库管线数据不存在");

    ErrorCode CHUNK_NOT_EXISTS = new ErrorCode(1_005_002_000, "知识片段不存在");

    ErrorCode CHUNK_STATUS_ERROR = new ErrorCode(1_005_002_001, "知识片段状态不正确");

    ErrorCode CHUNK_KB_NOT_VISIBLE = new ErrorCode(1_005_002_002, "无权访问该片段所属知识库");

}
