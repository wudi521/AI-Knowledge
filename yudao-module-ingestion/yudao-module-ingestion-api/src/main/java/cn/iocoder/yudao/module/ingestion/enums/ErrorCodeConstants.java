package cn.iocoder.yudao.module.ingestion.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 入库管线 错误码枚举
 * 错误码分段: 1_005_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode INGESTION_NOT_EXISTS = new ErrorCode(1_005_000_001, "入库管线数据不存在");

    ErrorCode CHUNK_NOT_EXISTS = new ErrorCode(1_005_002_000, "知识片段不存在");

}
