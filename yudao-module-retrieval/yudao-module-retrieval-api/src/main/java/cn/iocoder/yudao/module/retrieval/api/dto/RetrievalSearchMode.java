package cn.iocoder.yudao.module.retrieval.api.dto;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.Optional;

/**
 * Retrieval RPC 的显式执行模式白名单。
 *
 * <p>DTO 继续使用 String 保持 RPC/JSON 向后兼容；所有新调用方必须从本枚举取值。
 * 空 searchMode 是唯一允许进入 Legacy SearchService 的兼容信号；未知非空值不得静默回退旧链。</p>
 */
public enum RetrievalSearchMode {
    EXACT_TEXT_SEARCH,
    PLANNED_HYBRID;

    public static Optional<RetrievalSearchMode> parseExplicit(String raw) {
        if (StrUtil.isBlank(raw)) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignore) {
            return Optional.empty();
        }
    }

    public String code() {
        return name();
    }
}
