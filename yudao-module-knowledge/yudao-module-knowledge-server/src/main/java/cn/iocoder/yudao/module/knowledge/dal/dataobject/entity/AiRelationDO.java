package cn.iocoder.yudao.module.knowledge.dal.dataobject.entity;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 知识关系(SPO + 时间范围 + 权威 + 置信度)
 */
@TableName("ai_relation")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiRelationDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 主体实体 */
    private Long subjectEntityId;

    /** 谓词(如 REPORTS_TO/REFUND_PERIOD) */
    private String predicate;

    /** 客体实体(值型关系为 NULL) */
    private Long objectEntityId;

    /** 客体值(属性型关系, 如 30天) */
    private String objectValue;

    /** 有效期起始 */
    private LocalDate validFrom;

    /** 有效期截止 */
    private LocalDate validTo;

    /** 权威级别(高者优先) */
    private Integer authority;

    /** 置信度 */
    private BigDecimal confidence;

    /** 来源: MANUAL/LLM/RULE */
    private String source;

    /** 状态: ACTIVE/SUPERSEDED/ARCHIVED */
    private String status;

}
