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
    ErrorCode REVIEW_ITEM_NOT_EXISTS = new ErrorCode(1_004_000_005, "审核条目不存在");
    ErrorCode REVIEW_ITEM_STATUS_ERROR = new ErrorCode(1_004_000_006, "审核条目状态不允许该操作");
    ErrorCode REVIEW_REASON_REQUIRED = new ErrorCode(1_004_000_009, "驳回原因不能为空");
    ErrorCode VERSION_DOC_MISMATCH = new ErrorCode(1_004_000_012, "版本与文档不匹配");
    ErrorCode REVIEW_EXTRACT_FAILED = new ErrorCode(1_004_000_013, "审核条目抽取失败(LLM 输出无法解析), 请重试");
    // 注意: 1_004_000_009 已被 REVIEW_REASON_REQUIRED 占用, 冲突错误码顺延 014/015/016
    ErrorCode CONFLICT_NOT_EXISTS = new ErrorCode(1_004_000_014, "冲突记录不存在");
    ErrorCode CONFLICT_STATUS_ERROR = new ErrorCode(1_004_000_015, "冲突记录状态不允许该操作");
    ErrorCode CONFLICT_PENDING_EXISTS = new ErrorCode(1_004_000_016, "存在待裁决冲突, 无法发布");
    ErrorCode KB_NOT_VISIBLE = new ErrorCode(1_004_000_017, "知识库不可见或已过期");

}
