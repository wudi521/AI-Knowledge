package cn.iocoder.yudao.module.eval.dal.dataobject.task;

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

import java.time.LocalDateTime;

/**
 * AI 评测任务 DO(ai_eval_task)
 * <p>
 * 一次评测 = 一个任务(选择测试集/知识库 → 逐题 evidence 评估落库 → 指标汇总 → 闸门判定)
 */
@TableName("ai_eval_task")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalTaskDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 测试集编号 */
    private Long suiteId;

    /** 模型 */
    private String model;

    /** Prompt 版本 */
    private String promptVer;

    /** 状态: RUNNING / DONE / FAILED(见 EvalRunner 常量) */
    private String status;

    /** 指标快照(JSON 字符串, 任务 5 汇总后写入) */
    private String metrics;

    /** 评测知识库(为空 = 全部用例) */
    private Long kbId;

    /** 考题数(开始评测时写入) */
    private Integer caseCount;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 闸门是否通过(任务 5 判定后写入) */
    private Integer gatePass;

    /** 失败用例明细(JSON 字符串, 任务 5 汇总后写入) */
    private String failCases;

}
