package cn.iocoder.yudao.module.knowledge.dal.dataobject.entity;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 知识实体(规范化名称唯一; 别名消歧)
 */
@TableName("ai_entity")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiEntityDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 来源知识库(可空=全局实体) */
    private Long kbId;

    /** 实体类型: PERSON/PRODUCT/ORG/POLICY/GENERIC */
    private String entityType;

    /** 规范化名称(唯一) */
    private String canonicalName;

    /** 归一化名称(小写去空格, 消歧用) */
    private String normalizedName;

    /** 扩展属性(JSON) */
    private String attributes;

    /** 状态: ACTIVE/MERGED/SPLIT/ARCHIVED */
    private String status;

    /** 置信度 */
    private BigDecimal confidence;

}
