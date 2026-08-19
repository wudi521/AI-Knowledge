package cn.iocoder.yudao.module.chat.dal.dataobject.feedback;

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
 * AI 反馈 DO(ai_feedback)
 * <p>
 * type: THUMB_UP 点赞 / THUMB_DOWN 点踩(见 {@link cn.iocoder.yudao.module.chat.enums.feedback.FeedbackTypeEnum});
 * 点踩反馈落库后异步生成评测用例, 编号回填 evalCaseId(反馈→考题闭环)。
 */
@TableName("ai_feedback")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiFeedbackDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息编号(被反馈的 AI 消息) */
    private Long messageId;

    /** 类型: THUMB_UP 点赞 / THUMB_DOWN 点踩 */
    private String type;

    /** 说明(点踩原因等) */
    private String note;

    /** 生成的评测用例编号(点踩闭环回填, 未生成/失败为空) */
    private Long evalCaseId;

}
