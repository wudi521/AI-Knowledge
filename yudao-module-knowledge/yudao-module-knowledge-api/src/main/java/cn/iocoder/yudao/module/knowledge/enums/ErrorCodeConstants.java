package cn.iocoder.yudao.module.knowledge.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 知识平台 错误码枚举
 * 错误码分段: 1_004_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode DOCUMENT_NOT_EXISTS = new ErrorCode(1_001_002_000, "文档不存在");

    ErrorCode KNOWLEDGE_NOT_EXISTS = new ErrorCode(1_004_000_001, "知识平台数据不存在");
    ErrorCode VERSION_NOT_EXISTS = new ErrorCode(1_004_000_002, "文档版本不存在");
    ErrorCode VERSION_STATUS_ERROR = new ErrorCode(1_004_000_003, "文档版本状态不允许该操作");
    ErrorCode VERSION_PUBLISH_BLOCKED = new ErrorCode(1_004_000_004, "存在未处理完的必审条目或待裁决冲突,无法发布");
    ErrorCode VERSION_DOC_MISMATCH = new ErrorCode(1_004_000_012, "版本与文档不匹配");
    ErrorCode REVIEW_EXTRACT_FAILED = new ErrorCode(1_004_000_013, "审核条目抽取失败(LLM 输出无法解析), 请重试");

}
