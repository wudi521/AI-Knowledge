package cn.iocoder.yudao.module.evidence.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 证据冲突(冲突判定器输出: 同一事实点说法相互矛盾的证据对)
 * <p>
 * 约定: evidenceIndexA/evidenceIndexB 为证据在输入列表(去重后、按得分降序)中的位置索引, 恒有 A &lt; B。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Conflict {

    /** 证据 A 在输入列表中的位置索引 */
    private Integer evidenceIndexA;

    /** 证据 B 在输入列表中的位置索引 */
    private Integer evidenceIndexB;

    /** 矛盾原因说明(LLM 生成) */
    private String reason;

}
