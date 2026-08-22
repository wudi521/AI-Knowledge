package cn.iocoder.yudao.module.evidence.dal.dataobject.evidence;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 回答引用(一次回答的引用汇总, F1)
 */
@TableName("ai_answer_citation")
@Data
@EqualsAndHashCode(callSuper = true)
public class AnswerCitationDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 评估链路追踪号 */
    private String traceId;

    /** 问题 */
    private String query;

    /** 回答 SHA-256 */
    private String answerHash;

    /** 引用片段编号列表(JSON) */
    private String citationChunkIds;

}
