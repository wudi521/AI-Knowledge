package cn.iocoder.yudao.module.evidence.dal.dataobject.evidence;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 回答断言(claim → 证据片段, F1 Evidence Lineage)
 */
@TableName("ai_answer_claim")
@Data
@EqualsAndHashCode(callSuper = true)
public class AnswerClaimDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 评估链路追踪号 */
    private String traceId;

    /** 断言原文 */
    private String claimText;

    /** 判定: SUPPORTED/UNSUPPORTED */
    private String verdict;

    /** 支撑证据片段(-1/空=无支撑) */
    private Long evidenceChunkId;

}
