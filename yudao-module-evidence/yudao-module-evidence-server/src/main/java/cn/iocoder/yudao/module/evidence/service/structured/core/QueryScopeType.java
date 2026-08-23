package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * Structured Query 查询范围类型(Platform Core 领域无关)。
 */
public enum QueryScopeType {

    /** 当前知识库(整库范围) */
    CURRENT_KB,

    /** 当前文档 */
    CURRENT_DOCUMENT,

    /** 明确文档集合(已解析出 documentId 集合) */
    DOCUMENT_SET,

    /** 明确实体集合(非文档类实体, 如产品/设备) */
    ENTITY_SET,

    /** 会话上下文对象集合(由 Context Resolver 消解为 DOCUMENT_SET/ENTITY_SET) */
    CONVERSATION_CONTEXT,

    /** 时间范围 */
    TIME_RANGE,

    /** 地域范围 */
    REGION,

    /** 用户全部可见范围(跨知识库) */
    ALL_VISIBLE

}
