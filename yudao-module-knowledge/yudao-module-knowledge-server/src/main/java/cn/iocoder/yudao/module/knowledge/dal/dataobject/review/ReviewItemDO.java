package cn.iocoder.yudao.module.knowledge.dal.dataobject.review;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 审核条目(对应 ai_review_item, LLM 从 chunk 抽取, 逐条人工审核)
 */
@TableName("ai_review_item")
@KeySequence("ai_review_item_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewItemDO extends TenantBaseDO {

    /** 编号 */
    @TableId
    private Long id;
    /** 版本编号 */
    private Long versionId;
    /** 文档编号 */
    private Long docId;
    /** 来源 Chunk 编号 */
    private Long chunkId;
    /** 类型: POLICY/PRICE/LEGAL/FAQ/SOP */
    private String itemType;
    /** 条目主题 */
    private String title;
    /** 条目内容 */
    private String content;
    /** 风险: HIGH/MED/LOW */
    private String riskLevel;
    /** AI 置信度 */
    private BigDecimal aiConfidence;
    /** 是否必审: 1必审 0可自动 */
    private Boolean mustReview;
    /** 状态: PENDING/APPROVED/REJECTED */
    private String status;
    /** 审核人 */
    private String reviewer;
    /** 双人复核第二人(价格类) */
    private String reviewer2;
    /** 驳回原因 */
    private String rejectReason;
    /** 审核时间 */
    private LocalDateTime reviewTime;

}
