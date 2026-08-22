package cn.iocoder.yudao.module.knowledge.dal.dataobject.entity;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 实体别名(消歧: 小张/张三/张工 → 同一实体)
 */
@TableName("ai_entity_alias")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiEntityAliasDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 实体编号 */
    private Long entityId;

    /** 别名 */
    private String alias;

    /** 别名类型: SYNONYM/ABBREVIATION/NICKNAME */
    private String aliasType;

    /** 置信度 */
    private BigDecimal confidence;

    /** 来源: MANUAL/LLM/RULE */
    private String source;

}
