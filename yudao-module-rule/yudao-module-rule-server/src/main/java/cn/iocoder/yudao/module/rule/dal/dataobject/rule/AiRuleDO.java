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
 * AI 硬规则 DO(租户级表, 同 rule_key 多版本: 1 启用 + 可选灰度)
 * <p>
 * 继承 TenantBaseDO → 租户过滤自动生效(无需 @TenantIgnore)
 */
@TableName("ai_rule")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRuleDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务键(如 warranty-condition/delivery-condition) */
    private String ruleKey;

    /** 名称 */
    private String name;

    /** 说明 */
    private String description;

    /** DRL 规则文本 */
    private String drlContent;

    /** 版本号(同 key 自增) */
    private Integer version;

    /** 状态: 0=停用 1=启用(全量) 2=灰度中(带 grayTenantIds) */
    private Integer status;

    /** 灰度租户列表(JSON 数组) */
    private String grayTenantIds;

}
