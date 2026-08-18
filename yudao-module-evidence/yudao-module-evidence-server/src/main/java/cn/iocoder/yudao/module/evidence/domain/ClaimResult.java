package cn.iocoder.yudao.module.evidence.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 断言验证结果(Claim 验证器输出: 回答逐句拆分后的断言 + 是否有证据支撑)
 * <p>
 * 约定:
 * <ul>
 *     <li>text: 断言句子原文(回答中的一句);</li>
 *     <li>verdict: SUPPORTED(有证据支撑) / UNSUPPORTED(无证据支撑);</li>
 *     <li>evidenceIndex: 支撑该句的证据在证据列表中的位置索引(0 起; 无支撑为 -1)。
 *         与生成器提示词中的 1 起 [C1]..[CN] 引用相差 1: [C1] ↔ evidenceIndex=0。</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClaimResult {

    /** 断言句子原文 */
    private String text;

    /** 判定: SUPPORTED / UNSUPPORTED */
    private String verdict;

    /** 支撑证据在证据列表中的位置索引(0 起; -1 = 无支撑) */
    private Integer evidenceIndex;

}
