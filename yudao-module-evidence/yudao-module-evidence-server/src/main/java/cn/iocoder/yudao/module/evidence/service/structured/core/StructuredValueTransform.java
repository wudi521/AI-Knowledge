package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * 受控结构化值变换。这里枚举的是系统真正可执行的数据运算，不是用户 Intent。
 * Domain Field 通过 allowedTransforms 显式声明可用集合，Planner 只能组合已声明能力。
 */
public enum StructuredValueTransform {
    /** 字符长度；返回 INTEGER。 */
    LENGTH,
    /** 日期年份；返回 INTEGER。 */
    YEAR,
    /** 日期月份 1-12；返回 INTEGER。 */
    MONTH,
    /** 日期年月 yyyy-MM；返回 STRING，可稳定排序/分组。 */
    YEAR_MONTH,
    /** 多值字段包含的元素数量；返回 INTEGER。 */
    VALUE_COUNT,
    /** 人名姓氏；采用保守解析策略，无法可靠解析时返回空并由完整性规则 fail-closed。 */
    PERSON_SURNAME
}
