package cn.iocoder.yudao.module.chat.dal.dataobject.message;

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

import java.math.BigDecimal;

/**
 * AI 会话消息 DO(ai_message, 每条对话 1 行)
 * <p>
 * citations / entities 为 JSON 列, 与 ai_evidence_eval.claims 一致采用 String 字段存储,
 * 序列化由调用方通过 hutool JSONUtil 完成。
 */
@TableName("ai_message")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMessageDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话编号 */
    private Long conversationId;

    /** 角色: USER 用户 / AI 机器人 / SYSTEM 系统 */
    private String role;

    /** 消息内容 */
    private String content;

    /** 引用证据(JSON 数组字符串, AI 消息) */
    private String citations;

    /** 意图(USER 消息识别结果) */
    private String intent;

    /** 实体(JSON 字符串, USER 消息实体抽取结果) */
    private String entities;

    /** 置信度(0~1, 4 位小数, AI 消息) */
    private BigDecimal confidence;

    /** 链路追踪号(证据评估 traceId, AI 消息) */
    private String traceId;

}
