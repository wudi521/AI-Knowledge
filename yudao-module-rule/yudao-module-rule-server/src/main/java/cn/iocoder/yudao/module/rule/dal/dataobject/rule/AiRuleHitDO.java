package cn.iocoder.yudao.module.rule.dal.dataobject.rule;

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

/**
 * AI 规则命中留痕 DO(租户级表; 命中硬规则时记录)
 * <p>
 * 继承 TenantBaseDO → 租户过滤自动生效(无需 @TenantIgnore)
 */
@TableName("ai_rule_hit")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRuleHitDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务键 */
    private String ruleKey;

    /** 命中规则版本 */
    private Integer ruleVersion;

    /** 问题 */
    private String query;

    /** 事实(JSON) */
    private String facts;

    /** 规则结论(JSON) */
    private String conclusion;

    /** LLM 结论(预留冲突对比) */
    private String llmConclusion;

    /** 是否以规则为准偏离 LLM(预留) */
    private Boolean deviated;

}
