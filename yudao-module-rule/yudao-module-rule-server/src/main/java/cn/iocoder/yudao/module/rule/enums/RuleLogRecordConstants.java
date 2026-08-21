package cn.iocoder.yudao.module.rule.enums;

/**
 * rule 模块操作日志常量(审计: 写操作留痕, 落 system_operate_log)
 * <p>
 * 模板使用 mzt-logapi SpEL({{#参数名.属性}}), 参数名必须与方法签名一致;
 * 删除/启停等无名称入参的操作, 先查出对象再操作, 用 {{#rule.name}} 引用
 */
public interface RuleLogRecordConstants {

    // ========== AI 规则 ==========
    String RULE_TYPE = "AI 规则";
    String RULE_CREATE_SUB_TYPE = "创建规则";
    String RULE_CREATE_SUCCESS = "创建了规则【{{#req.name}}】(key={{#req.ruleKey}})";
    String RULE_UPDATE_SUB_TYPE = "编辑规则";
    String RULE_UPDATE_SUCCESS = "编辑了规则【{{#req.name}}】(id={{#req.id}})";
    String RULE_ENABLE_SUB_TYPE = "启用规则";
    String RULE_ENABLE_SUCCESS = "启用了规则【{{#rule.name}}】(v{{#rule.version}})";
    String RULE_GRAY_ENABLE_SUB_TYPE = "灰度发布规则";
    String RULE_GRAY_ENABLE_SUCCESS = "灰度发布了规则【{{#rule.name}}】(v{{#rule.version}}, 租户={{#tenantIds}})";
    String RULE_GRAY_OFF_SUB_TYPE = "关闭规则灰度";
    String RULE_GRAY_OFF_SUCCESS = "关闭了规则【{{#rule.name}}】(v{{#rule.version}})的灰度";
    String RULE_DELETE_SUB_TYPE = "删除规则";
    String RULE_DELETE_SUCCESS = "删除了规则【{{#rule.name}}】(v{{#rule.version}})";

}
