package cn.iocoder.yudao.module.evidence.dal.dataobject.evidence;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * AI 证据评估会话 DO(ai_evidence_eval, 每次 evaluate 调用 1 行)
 */
@TableName("ai_evidence_eval")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceEvalDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评估会话追踪号 */
    private String traceId;

    /** 问题 */
    private String query;

    /** 是否可作答(1=是, 0=否) */
    private Integer answerable;

    /** 置信度(0~1, 4 位小数) */
    private BigDecimal confidence;

    /** 拒绝原因 */
    private String refusalReason;

    /** 证据条数 */
    private Integer evidenceCount;

    /** 冲突数 */
    private Integer conflictCount;

    /** 生成的回答(claimFail 或不可作答时为 null) */
    private String answer;

    /** Claim 是否全部通过(1=通过, 0=未通过) */
    private Integer claimPass;

    /** Claim 验证结果(JSON 数组字符串) */
    private String claims;

    /** 冲突列表(JSON 数组字符串) */
    private String conflicts;

    /** 评估上下文快照(多轮历史 JSON 数组字符串, 单轮为 null) */
    private String history;

    /** 耗时(ms) */
    private Integer elapsedMs;

    /** 抽取的槽位值(JSON 数组字符串) */
    private String slots;

    /** 缺失必填槽位(JSON 数组字符串) */
    private String missingSlots;

    /** 反问句 */
    private String clarifyQuestion;

}
