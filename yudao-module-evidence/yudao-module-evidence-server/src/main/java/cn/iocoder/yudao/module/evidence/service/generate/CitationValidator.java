package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0-08 Citation Validator: 校验回答中所有 [Cn] 标注是否落在证据列表范围内。
 * <p>
 * 约束: 1 &lt;= n &lt;= evidence.size; 出现非法引用(越界 / 证据缺失)一律拒绝直接返回给用户,
 * 由调用方转保守拒答或降级, 防止 "回答写 [C2] 但 Evidence 列表只有一个" 的错位。
 */
@Slf4j
public final class CitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[C(\\d+)]");

    private CitationValidator() {
    }

    /**
     * 校验回答中的引用标注, 返回所有非法引用编号(空 = 全部合法)。
     *
     * @param answer        生成回答(可含 [Cn] 标注)
     * @param evidenceCount 证据列表条数
     */
    public static List<Integer> findInvalidCitations(String answer, int evidenceCount) {
        List<Integer> invalid = new ArrayList<>();
        if (StrUtil.isBlank(answer) || evidenceCount <= 0) {
            return invalid;
        }
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int n = Integer.parseInt(matcher.group(1));
            if (n < 1 || n > evidenceCount) {
                invalid.add(n);
            }
        }
        return invalid;
    }

    /** 回答中的引用标注是否全部合法 */
    public static boolean isValid(String answer, int evidenceCount) {
        return findInvalidCitations(answer, evidenceCount).isEmpty();
    }

}
