package cn.iocoder.yudao.module.evidence.domain;

import lombok.Builder;
import lombok.Data;

/**
 * 充分性判定结果(充分性判定器输出: 是否可作答 + 融合置信度 + 原因)
 * <p>
 * 约定:
 * <ul>
 *     <li>answerable=false 时 reason 必填(证据不足/存在冲突证据/产品不匹配/检索阻断等, 可组合);</li>
 *     <li>consultable = confidence &gt;= consult-threshold(配置), 独立于 answerable 暴露(0.5~0.75 区间为可转人工);</li>
 *     <li>confidence 恒为 0~1 融合得分(即使不可作答也计算, 供展示)。</li>
 * </ul>
 */
@Data
@Builder
public class Judgement {

    /** 是否可作答(结构化门禁 + 阈值融合判定) */
    private Boolean answerable;

    /** 证据充分度融合置信度(0~1) */
    private Double confidence;

    /** 判定原因(answerable=false 时填充, 可组合: "证据不足(需至少X条);存在冲突证据;产品不匹配" 等) */
    private String reason;

    /** 参与判定的证据条数 */
    private Integer evidenceCount;

    /** 参与判定的冲突条数 */
    private Integer conflictCount;

    /** 是否可转人工咨询(confidence &gt;= consult-threshold) */
    private Boolean consultable;

}
