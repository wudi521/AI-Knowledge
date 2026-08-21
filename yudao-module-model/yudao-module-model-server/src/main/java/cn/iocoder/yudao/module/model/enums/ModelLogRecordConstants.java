package cn.iocoder.yudao.module.model.enums;

/**
 * model 模块操作日志常量(审计: 全操作留痕, 落 system_operate_log)
 * <p>
 * 模板变量约定: 仅可引用 方法参数 与 LogRecordContext.putVariable 注册的变量;
 * 方法内局部对象(如 insert 后的 DO)必须 putVariable 注册后才能在模板中访问。
 */
public interface ModelLogRecordConstants {

    // ========== AI 模型配置 ==========
    String MODEL_CONFIG_TYPE = "AI 模型配置";
    String MODEL_CONFIG_CREATE_SUB_TYPE = "创建模型配置";
    String MODEL_CONFIG_CREATE_SUCCESS = "创建了模型配置【{{#createReqVO.name}}】(type={{#createReqVO.type}}, scenario={{#createReqVO.scenario}})";
    String MODEL_CONFIG_UPDATE_SUB_TYPE = "编辑模型配置";
    String MODEL_CONFIG_UPDATE_SUCCESS = "编辑了模型配置【{{#updateReqVO.name}}】";
    String MODEL_CONFIG_DELETE_SUB_TYPE = "删除模型配置";
    String MODEL_CONFIG_DELETE_SUCCESS = "删除了模型配置【{{#config.name}}】";

    // ========== AI 提示词 ==========
    String PROMPT_TYPE = "AI 提示词";
    String PROMPT_CREATE_SUB_TYPE = "创建提示词版本";
    String PROMPT_CREATE_SUCCESS = "创建了提示词【{{#req.name}}】版本 v{{#newVersion}}";
    String PROMPT_UPDATE_SUB_TYPE = "编辑提示词";
    String PROMPT_UPDATE_SUCCESS = "编辑了提示词【{{#req.name}}】(id={{#req.id}})";
    String PROMPT_ENABLE_SUB_TYPE = "启用提示词";
    String PROMPT_ENABLE_SUCCESS = "启用了提示词【{{#prompt.name}}】(v{{#prompt.version}})";
    String PROMPT_GRAY_ENABLE_SUB_TYPE = "灰度发布提示词";
    String PROMPT_GRAY_ENABLE_SUCCESS = "灰度发布了提示词【{{#prompt.name}}】(v{{#prompt.version}}, 租户={{#tenantIds}})";
    String PROMPT_GRAY_OFF_SUB_TYPE = "关闭提示词灰度";
    String PROMPT_GRAY_OFF_SUCCESS = "关闭了提示词【{{#prompt.name}}】(v{{#prompt.version}})的灰度";
    String PROMPT_DELETE_SUB_TYPE = "删除提示词";
    String PROMPT_DELETE_SUCCESS = "删除了提示词【{{#prompt.name}}】(v{{#prompt.version}})";

}
