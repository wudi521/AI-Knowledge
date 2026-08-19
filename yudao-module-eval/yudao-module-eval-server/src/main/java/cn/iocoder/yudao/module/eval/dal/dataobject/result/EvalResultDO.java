package cn.iocoder.yudao.module.eval.dal.dataobject.result;

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
 * AI 评测逐题结果 DO(ai_eval_result)
 * <p>
 * 执行器(EvalRunner)逐题落原始数据(可作答性/置信度/回答/追踪号/检索结果顺序),
 * 指标(recall_at_5/mrr/ndcg/faithfulness/hallucination_rate/citation_accuracy/passed)
 * 由 MetricCalculator(任务 4)基于 {@link #resultChunks} 与标准证据计算后回填。
 */
@TableName("ai_eval_result")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalResultDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评测任务编号 */
    private Long taskId;

    /** 考题编号 */
    private Long caseId;

    /** 是否可作答(充分性判定) */
    private Boolean answerable;

    /** 充分度(0~1) */
    private Double confidence;

    /** Recall@5 */
    private Double recallAt5;

    /** MRR */
    private Double mrr;

    /** NDCG@5 */
    private Double ndcg;

    /** 忠实度 */
    private Double faithfulness;

    /** 幻觉率 */
    private Double hallucinationRate;

    /** 引用准确率 */
    private Double citationAccuracy;

    /** 是否达标 */
    private Boolean passed;

    /** 未达标原因 */
    private String failReasons;

    /** 模型回答 */
    private String answer;

    /** 评估链路追踪号 */
    private String traceId;

    /** 检索结果顺序(JSON 数组字符串, evidence[] 按得分降序 → chunkId 有序列表, 供指标计算) */
    private String resultChunks;

}
