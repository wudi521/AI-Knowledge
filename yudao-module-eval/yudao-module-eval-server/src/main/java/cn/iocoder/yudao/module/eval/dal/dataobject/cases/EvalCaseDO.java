package cn.iocoder.yudao.module.eval.dal.dataobject.cases;

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
 * AI 评测用例 DO(ai_eval_case)
 * <p>
 * 来源: 人工录入 / chat 反馈转用例(createCaseFromFeedback, 标准答案与证据待人工补充)
 */
@TableName("ai_eval_case")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 问题 */
    private String question;

    /** 标准答案 */
    private String goldAnswer;

    /** 标准证据(JSON 数组字符串, 如 [2101,2093]; 调用方负责序列化) */
    private String goldChunks;

    /** 来源反馈编号(ai_feedback.id, 反馈转用例时记录) */
    private Long sourceFeedback;

    /** 知识库编号 */
    private Long kbId;

    /** 分类(如 综合/保修/收费, 便于用例集筛选) */
    private String category;

}
