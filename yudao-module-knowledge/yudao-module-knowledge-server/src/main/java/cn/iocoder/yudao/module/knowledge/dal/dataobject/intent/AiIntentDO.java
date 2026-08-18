package cn.iocoder.yudao.module.knowledge.dal.dataobject.intent;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * AI 意图 DO(租户隔离: 与 ai_knowledge_base 一致)
 */
@TableName("ai_intent")
@KeySequence("ai_intent_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiIntentDO extends TenantBaseDO {

    /** 编号 */
    @TableId
    private Long id;
    /** 知识库编号 */
    private Long kbId;
    /** 意图名(如 保修/退款/产品推荐) */
    private String name;
    /** 意图说明(LLM总结或手填, 供分类参考) */
    private String description;
    /** 来源: LLM_AUTO/MANUAL */
    private String source;
    /** 状态: 0启用/1停用 */
    private Integer status;

}
