package cn.iocoder.yudao.module.knowledge.dal.dataobject.conflict;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 版本冲突记录(规则粗筛 + LLM 判定 + 人工裁决, BR-008)
 */
@TableName("ai_conflict")
@KeySequence("ai_conflict_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflictDO extends TenantBaseDO {

    /** 编号 */
    @TableId
    private Long id;
    /** 新版本编号 */
    private Long versionId;
    /** 旧已发布版本编号 */
    private Long oldVersionId;
    /** 文档编号 */
    private Long docId;
    /** 关联审核条目 */
    private Long itemId;
    /** 冲突主题 */
    private String title;
    /** 旧版本表述 */
    private String oldContent;
    /** 新版本表述 */
    private String newContent;
    /** 规则粗筛命中 */
    private Boolean ruleHit;
    /** LLM 判定: CONFLICT/NO_CONFLICT */
    private String llmJudgement;
    /** LLM 判定理由 */
    private String llmReason;
    /** 状态: PENDING/RESOLVED_NEW/RESOLVED_OLD */
    private String status;
    /** 裁决人 */
    private String resolver;
    /** 裁决时间 */
    private LocalDateTime resolveTime;

}
