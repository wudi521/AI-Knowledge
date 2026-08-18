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
 * AI 证据记录 DO(ai_evidence, 每次 evaluate 每个证据 1 行)
 */
@TableName("ai_evidence")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRecordDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识片段编号 */
    private Long chunkId;

    /** 置信度(0~1, 4 位小数) */
    private BigDecimal confidence;

    /** 结论: SUPPORTED/UNSUPPORTED/WARN */
    private String verdict;

    /** 链路追踪号(关联 ai_evidence_eval.trace_id) */
    private String traceId;

}
