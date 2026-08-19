package cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge;

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
 * 知识库槽位定义 DO(租户隔离: 与 ai_knowledge_base 一致)
 */
@TableName("ai_knowledge_base_slot")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiKnowledgeBaseSlotDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 知识库编号 */
    private Long kbId;
    /** 槽位编码(如 brand/faultType/purchaseTime) */
    private String slotCode;
    /** 槽位名(如 品牌型号) */
    private String slotName;
    /** 抽取说明(喂给 LLM 的定义) */
    private String description;
    /** 是否必填(1=缺则反问) */
    private Boolean required;
    /** 排序(组反问句顺序) */
    private Integer sort;
    /** 状态: 0=启用 1=禁用(CommonStatusEnum) */
    private Integer status;

}
