package cn.iocoder.yudao.module.knowledge.dal.dataobject.scope;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 知识业务范围(D2: 省市/产品/渠道/客户分段, 检索硬过滤的一等模型)
 */
@TableName("ai_knowledge_scope")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiKnowledgeScopeDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 知识库编号 */
    private Long kbId;

    /** 范围类型: PROVINCE/CITY/PRODUCT/CHANNEL/CUSTOMER_SEGMENT */
    private String scopeType;

    /** 范围编码(如 110000/北京/套餐A) */
    private String scopeCode;

    /** 优先级(精确城市>省级>全国; 小者优先) */
    private Integer scopePriority;

    /** 生效起始(空=永久) */
    private LocalDateTime effectiveFrom;

    /** 生效截止(空=永久) */
    private LocalDateTime effectiveTo;

}
