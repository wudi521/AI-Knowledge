package cn.iocoder.yudao.module.evidence.service.structured.core;

/** Structured Filter Tree 白名单运算符。禁止透传任意 SQL/脚本。 */
public enum FilterOperator {
    EQ,
    NE,
    CONTAINS,
    STARTS_WITH,
    IN,
    EXISTS
}
