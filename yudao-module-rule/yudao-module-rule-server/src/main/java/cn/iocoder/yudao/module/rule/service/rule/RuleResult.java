package cn.iocoder.yudao.module.rule.service.rule;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则结论事实(Drools insert 用; 领域无关: code/text 全是数据)
 * <p>
 * DRL 约定(规则内容为数据, 编写时需显式 import):
 * <pre>
 * package rules
 * import java.util.Map; // Map 事实类型(非 java.lang, 必须显式导入)
 * import cn.iocoder.yudao.module.rule.service.rule.RuleResult;
 *
 * rule "跨省配送时效"
 * when
 *   $f: Map($f["region"] == "跨省")
 * then
 *   insert(new RuleResult("delivery-3d", "跨省配送时效 3 天")); // 参数顺序 = (code, text)
 * end
 * </pre>
 * 引擎收集会话中全部 RuleResult 作为命中结论。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleResult {

    /** 结论编码(可空, 如 delivery-3d) */
    private String code;

    /** 结论文本(如 跨省配送时效 3 天) */
    private String text;

}
