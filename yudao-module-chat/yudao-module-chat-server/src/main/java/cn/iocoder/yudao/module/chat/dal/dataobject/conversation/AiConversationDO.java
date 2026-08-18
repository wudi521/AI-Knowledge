package cn.iocoder.yudao.module.chat.dal.dataobject.conversation;

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
 * AI 会话 DO(ai_conversation, 一个客户会话 1 行)
 */
@TableName("ai_conversation")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConversationDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 渠道(默认 WEB) */
    private String channel;

    /** 客户标识(默认 anonymous) */
    private String customerId;

    /** 状态: ACTIVE 进行中 / TRANSFERRED 待人工接单 / CLOSED 已关闭 */
    private String status;

    /** 会话意图 */
    private String intent;

    /** 会话摘要(转人工时记录) */
    private String summary;

    /** 转人工原因 */
    private String transferReason;

    /** 接单客服编号(人工接单后记录) */
    private Long operatorId;

}
