package cn.iocoder.yudao.module.eval.framework.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 评测平台业务配置。 */
@Data
@ConfigurationProperties(prefix = "yudao.eval")
public class EvalProperties {

    private String model = "evidence-v1";
    private Gate gate = new Gate();
    private Runner runner = new Runner();

    @Data
    public static class Gate {
        private boolean enabled = true;
        private double recallAt5 = 0.9;
        private double mrr = 0.8;
        private double ndcg = 0.8;
        private double faithfulness = 0.95;
        private double hallucination = 0.02;
        private double citationAccuracy = 0.97;
    }

    @Data
    public static class Runner {
        private int maxParallel = 2;

        /**
         * 环境型 E2E 单题超时。评测不能容忍 2~5 分钟的“最终成功”，否则性能退化无法进入 Gate。
         * 30s 仍高于当前 QueryPlan 默认 20s，给 RPC/落库留出余量。
         */
        private long timeoutMs = 30_000L;
    }

}
