package cn.iocoder.yudao.module.eval.framework.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 评测平台业务配置
 * <p>
 * 绑定 yudao.eval.* 配置项(kebab-case 自动映射 camelCase), 供达标闸门(MetricCalculator)与执行器(EvalRunner)读取,
 * 全部阈值来自配置, 代码不硬编码。对齐 evidence 模块 {@code EvidenceProperties} 模式。
 */
@Data
@ConfigurationProperties(prefix = "yudao.eval")
public class EvalProperties {

    /** 默认评测模型(发起任务时写入 ai_eval_task.model) */
    private String model = "evidence-v1";

    /** 逐题达标闸门配置 */
    private Gate gate = new Gate();

    /** 评测执行器配置 */
    private Runner runner = new Runner();

    /**
     * 逐题达标闸门: 各指标阈值(全部达标才 passed=true)
     */
    @Data
    public static class Gate {

        /** 闸门开关: false 时仅计算指标, 不做达标判定(全部视为通过) */
        private boolean enabled = true;

        /** Recall@5 阈值(检索命中率) */
        private double recallAt5 = 0.9;

        /** MRR 阈值(首个命中位置倒数) */
        private double mrr = 0.8;

        /** NDCG@5 阈值(排序质量) */
        private double ndcg = 0.8;

        /** 忠实度阈值(SUPPORTED 断言占比) */
        private double faithfulness = 0.95;

        /** 幻觉率阈值(UNSUPPORTED 断言占比, 越小越好) */
        private double hallucination = 0.02;

        /** 引用准确率阈值(引用命中标准证据占比) */
        private double citationAccuracy = 0.97;

    }

    /**
     * 评测执行器配置
     */
    @Data
    public static class Runner {

        /** 最大并行题数(当前执行器为单线程顺序执行, 预留扩展) */
        private int maxParallel = 2;

        /** 单题超时(ms) */
        private long timeoutMs = 300000;

    }

}
