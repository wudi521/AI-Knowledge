package cn.iocoder.yudao.module.chat.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 对话工作台 错误码枚举
 * 错误码分段: 1_003_000_000 起
 */
public interface ErrorCodeConstants {

    ErrorCode CHAT_NOT_EXISTS = new ErrorCode(1_003_000_001, "对话工作台数据不存在");
    ErrorCode CONVERSATION_NOT_EXISTS = new ErrorCode(1_003_000_002, "会话不存在");
    ErrorCode MESSAGE_NOT_EXISTS = new ErrorCode(1_003_000_003, "消息不存在");
    ErrorCode KNOWLEDGE_BASE_NOT_EXISTS = new ErrorCode(1_003_000_005, "知识库不存在或无权访问");
    ErrorCode CONVERSATION_CONTEXT_CONFLICT = new ErrorCode(1_003_000_006, "会话已绑定其他知识库，请新建会话");
    ErrorCode KNOWLEDGE_DOMAIN_UNAVAILABLE = new ErrorCode(1_003_000_007, "知识库领域信息不可用，请稍后重试");
    ErrorCode CONVERSATION_CONTEXT_STALE = new ErrorCode(1_003_000_008, "知识库领域已发生变化，请新建会话后继续问答");
    ErrorCode CONVERSATION_FORBIDDEN = new ErrorCode(1_003_000_009, "无权访问该会话");
    ErrorCode KNOWLEDGE_BASE_FORBIDDEN = new ErrorCode(1_003_000_010, "无权访问该知识库");
    ErrorCode KNOWLEDGE_DOMAIN_LOCKED = new ErrorCode(1_003_000_011, "该知识库领域已锁定，无法变更");
    ErrorCode DOCUMENT_NOT_FOUND = new ErrorCode(1_003_000_012, "文档不存在");
    ErrorCode DOCUMENT_NOT_PUBLISHED = new ErrorCode(1_003_000_013, "文档未发布，暂不可查询");
    ErrorCode PATENT_IDENTIFIER_NOT_FOUND = new ErrorCode(1_003_000_014, "未找到对应专利，请核实申请号或公布号");
    ErrorCode PATENT_CLAIM_NOT_FOUND = new ErrorCode(1_003_000_015, "该专利中未找到对应权利要求");
    ErrorCode EVIDENCE_INSUFFICIENT = new ErrorCode(1_003_000_016, "当前知识库中没有足够证据支持可靠回答");
    ErrorCode QUERY_OUT_OF_SCOPE = new ErrorCode(1_003_000_017, "该问题超出当前知识库范围，无法回答");
    ErrorCode MODEL_UNAVAILABLE = new ErrorCode(1_003_000_018, "AI 服务暂不可用，请稍后重试");
    ErrorCode RETRIEVAL_UNAVAILABLE = new ErrorCode(1_003_000_019, "检索服务暂不可用，请稍后重试");
    ErrorCode QUERY_TIMEOUT = new ErrorCode(1_003_000_020, "本次查询超时，已返回当前可用结果");
    ErrorCode FEEDBACK_RATING_INVALID = new ErrorCode(1_003_000_021, "反馈评价不正确(仅支持 HELPFUL/NOT_HELPFUL)");
    ErrorCode FEEDBACK_REASON_INVALID = new ErrorCode(1_003_000_022, "反馈原因不正确");
    ErrorCode FEEDBACK_NOT_ALLOWED = new ErrorCode(1_003_000_023, "无权反馈该消息");
    ErrorCode CHAT_STREAM_IN_FLIGHT = new ErrorCode(1_003_000_024, "已有进行中的流式问答，请等待完成或先停止");

}
