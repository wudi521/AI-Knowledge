package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;

import java.util.List;

/**
 * 在 Planner 决定 ANSWER 之后，对“现有观察是否真的足以回答 immutable OriginalGoal”做独立门控。
 *
 * <p>它不负责理解业务意图，也不选择工具；只检查证明强度与覆盖度，避免“相关事实”被误当成
 * “已经证明原问题”。生产实现可以使用独立 LLM evaluator；测试/兼容场景可使用 trustPlanner。</p>
 */
public interface AgentGoalEvaluator {

    Evaluation evaluate(String originalGoal,
                        List<AgentObservation> observations,
                        List<String> deterministicAnswers,
                        List<Evidence> evidences,
                        CapabilityInvocationContext context);

    enum Verdict {
        SATISFIED,
        INSUFFICIENT,
        NEED_MORE_INFO,
        EVALUATION_FAILED
    }

    record Evaluation(Verdict verdict, String reason, String message) {
        public static Evaluation satisfied(String reason) {
            return new Evaluation(Verdict.SATISFIED, reason, null);
        }

        public static Evaluation insufficient(String reason) {
            return new Evaluation(Verdict.INSUFFICIENT, reason, null);
        }

        public static Evaluation needMoreInfo(String reason, String message) {
            return new Evaluation(Verdict.NEED_MORE_INFO, reason, message);
        }

        public static Evaluation failed(String reason) {
            return new Evaluation(Verdict.EVALUATION_FAILED, reason, null);
        }
    }

    /** 仅供旧单测/非 Spring 构造兼容；正式运行必须注入独立 evaluator。 */
    static AgentGoalEvaluator trustPlanner() {
        return (goal, observations, deterministicAnswers, evidences, context) ->
                Evaluation.satisfied("compatibility evaluator trusts planner decision");
    }
}
