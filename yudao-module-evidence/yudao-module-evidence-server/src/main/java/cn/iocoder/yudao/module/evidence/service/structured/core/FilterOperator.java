package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.Optional;

/** Structured Filter Tree 白名单运算符。禁止透传任意 SQL/脚本。 */
public enum FilterOperator {
    EQ,
    NE,
    CONTAINS,
    STARTS_WITH,
    IN,
    EXISTS,
    GT,
    GTE,
    LT,
    LTE,
    BETWEEN;

    /**
     * 外部 Planner 的表达只允许在协议边界归一化一次。进入 QueryIntent 后始终使用枚举，
     * 避免 Planner、Validator、Executor 各自解析字符串而产生协议漂移。
     */
    public static Optional<FilterOperator> fromExternal(String value) {
        if (StrUtil.isBlank(value)) return Optional.empty();
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EQ", "=", "==", "EQUAL", "EQUALS", "EXACT", "IS", "TERM" -> Optional.of(EQ);
            case "NE", "!=", "<>", "NOT_EQUAL", "NOT_EQUALS", "IS_NOT" -> Optional.of(NE);
            case "CONTAIN", "CONTAINS", "LIKE", "INCLUDES" -> Optional.of(CONTAINS);
            case "START_WITH", "STARTS_WITH", "PREFIX" -> Optional.of(STARTS_WITH);
            case "IN" -> Optional.of(IN);
            case "EXIST", "EXISTS", "IS_NOT_NULL", "NOT_NULL" -> Optional.of(EXISTS);
            case "GT", ">", "GREATER_THAN" -> Optional.of(GT);
            case "GTE", ">=", "GREATER_THAN_OR_EQUAL", "GREATER_THAN_EQUALS" -> Optional.of(GTE);
            case "LT", "<", "LESS_THAN" -> Optional.of(LT);
            case "LTE", "<=", "LESS_THAN_OR_EQUAL", "LESS_THAN_EQUALS" -> Optional.of(LTE);
            case "BETWEEN", "RANGE" -> Optional.of(BETWEEN);
            default -> Optional.empty();
        };
    }
}
