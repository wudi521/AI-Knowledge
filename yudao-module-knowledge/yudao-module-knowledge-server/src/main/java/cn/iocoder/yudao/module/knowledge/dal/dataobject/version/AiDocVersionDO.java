package cn.iocoder.yudao.module.knowledge.dal.dataobject.version;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * AI 文档版本(对应 ai_doc_version, 状态机见 VersionStatusEnum)
 */
@TableName("ai_doc_version")
@KeySequence("ai_doc_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDocVersionDO extends TenantBaseDO {

    /** 编号 */
    @TableId
    private Long id;
    /** 文档编号 */
    private Long docId;
    /** 版本号: V1/V2/... */
    private String versionNo;
    /** 状态: DRAFT/REVIEW/PUBLISHED/EXPIRED/ARCHIVED */
    private String status;
    /** 生效开始时间 */
    private LocalDateTime effectiveFrom;
    /** 生效结束时间 */
    private LocalDateTime effectiveTo;
    /** 审核人 */
    private String reviewer;
    /** 冲突状态: 0无 1待裁决 2已裁决 */
    private Integer conflictStatus;
    /** 审核结果: APPROVED/REJECTED */
    private String reviewResult;
    /** 审核意见 */
    private String reviewComment;

}
