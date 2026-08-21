package cn.iocoder.yudao.module.knowledge.enums;

/**
 * knowledge 模块操作日志常量(审计: 核心管理写操作留痕, 落 system_operate_log)
 *
 * <p>模板 SpEL 变量来源: 方法参数(编译期 -parameters) 或 {@code LogRecordContext.putVariable} 注册的对象;
 * 引用不存在的变量会抛 SpEL 异常导致该条日志丢弃, 故模板变量必须与方法签名或 putVariable 严格一致。
 */
public interface KnowledgeLogRecordConstants {

    // ========== 知识库 ==========
    String KB_TYPE = "AI 知识库";
    String KB_CREATE_SUB_TYPE = "创建知识库";
    String KB_CREATE_SUCCESS = "创建了知识库【{{#createReqVO.name}}】";
    String KB_UPDATE_SUB_TYPE = "编辑知识库";
    String KB_UPDATE_SUCCESS = "编辑了知识库【{{#updateReqVO.name}}】(id={{#updateReqVO.id}})";
    String KB_DELETE_SUB_TYPE = "删除知识库";
    String KB_DELETE_SUCCESS = "删除了知识库【{{#kb?.name}}】";

    // ========== AI 知识文档 ==========
    String DOC_TYPE = "AI 知识文档";
    String DOC_CREATE_SUB_TYPE = "上传文档";
    String DOC_CREATE_SUCCESS = "上传了文档【{{#createReqVO.name}}】(知识库 id={{#createReqVO.kbId}})";
    String DOC_DELETE_SUB_TYPE = "删除文档";
    String DOC_DELETE_SUCCESS = "删除了文档【{{#doc.name}}】";

    // ========== 知识发布 ==========
    String PUBLISH_TYPE = "知识发布";
    String PUBLISH_SUB_TYPE = "发布版本";
    String PUBLISH_SUCCESS = "发布了知识库【{{#kb?.name}}】的文档【{{#doc?.name}}】版本 v{{#version.versionNo}}";
    String REJECT_VERSION_SUB_TYPE = "驳回版本";
    String REJECT_VERSION_SUCCESS = "驳回了文档【{{#doc?.name}}】版本 v{{#version.versionNo}}";

    // ========== 知识审核 ==========
    String REVIEW_TYPE = "知识审核";
    String REVIEW_APPROVE_SUB_TYPE = "审核通过";
    String REVIEW_APPROVE_SUCCESS = "审核通过了 1 条知识条目【{{#item.title}}】(id={{#id}})";
    String REVIEW_REJECT_SUB_TYPE = "审核驳回";
    String REVIEW_REJECT_SUCCESS = "审核驳回了 1 条知识条目【{{#item.title}}】(id={{#id}})";

    // ========== 冲突裁决 ==========
    String CONFLICT_TYPE = "冲突裁决";
    String CONFLICT_RESOLVE_SUB_TYPE = "裁决冲突";
    String CONFLICT_RESOLVE_SUCCESS = "裁决了冲突【{{#conflict.title}}】(id={{#conflictId}}) 结果: {{#resolveResult}}";

}
