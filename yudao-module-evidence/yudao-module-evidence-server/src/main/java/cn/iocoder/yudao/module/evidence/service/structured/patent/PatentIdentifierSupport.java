package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 专利标识符的唯一协议定义，供 Domain Schema、Planner、Resolver 和 Answerer 共用。 */
public final class PatentIdentifierSupport {

    /** 中国新旧申请号以及 PCT 申请号；标识符始终按字符串处理。 */
    public static final String APPLICATION_NO_REGEX =
            "(?i)(?<![A-Z0-9])(?:(?:20\\d{10}|\\d{8})\\.\\d|PCT/[A-Z]{2}\\d{4}/\\d{4,8})(?![A-Z0-9])";
    /** 中国公布/公告号，兼容空格及 A/A1/B/B1 等 kind code。 */
    public static final String PUBLICATION_NO_REGEX =
            "(?i)(?<![A-Z0-9])CN\\s*\\d{7,12}\\s*[A-Z]\\d?(?![A-Z0-9])";

    public static final Pattern APPLICATION_NO = Pattern.compile(APPLICATION_NO_REGEX);
    public static final Pattern PUBLICATION_NO = Pattern.compile(PUBLICATION_NO_REGEX);

    private PatentIdentifierSupport() {
    }

    public static List<String> applicationPatterns() {
        return List.of(APPLICATION_NO_REGEX);
    }

    public static List<String> publicationPatterns() {
        return List.of(PUBLICATION_NO_REGEX);
    }

    public static String normalize(String value) {
        return StrUtil.isBlank(value) ? null : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
