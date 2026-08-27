package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    /** 生产 LLM evaluator 返回 true；兼容测试 evaluator 不占模型预算。 */
    default boolean consumesLlmCall() {
        return true;
    }

    enum Verdict {
        SATISFIED,
        INSUFFICIENT,
        NEED_MORE_INFO,
        EVALUATION_FAILED
    }

    /**
     * supportingReferenceIds 是“最终证明集 / proof frontier”。
     *
     * <p>历史 observations 可以包含失败、空结果、被后续 replan 纠正的中间事实以及仅用于定位候选的语义证据；
     * SATISFIED 时必须尽量明确指出真正支持 OriginalGoal 的 Reference，最终回答层只消费这组引用，避免把
     * 已被纠正的旧结果或无关证据重新拼回答案。</p>
     */
    record Evaluation(Verdict verdict, String reason, String message, List<String> supportingReferenceIds) {
        public Evaluation {
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            if (supportingReferenceIds != null) {
                for (String value : supportingReferenceIds) {
                    if (value != null && !value.isBlank()) unique.add(value.trim());
                }
            }
            supportingReferenceIds = List.copyOf(new ArrayList<>(unique));
        }

        /** 兼容旧单测/实现。 */
        public Evaluation(Verdict verdict, String reason, String message) {
            this(verdict, reason, message, List.of());
        }

        public static Evaluation satisfied(String reason) {
            return new Evaluation(Verdict.SATISFIED, reason, null, List.of());
        }

        public static Evaluation satisfied(String reason, List<String> supportingReferenceIds) {
            return new Evaluation(Verdict.SATISFIED, reason, null, supportingReferenceIds);
        }

        public static Evaluation insufficient(String reason) {
            return new Evaluation(Verdict.INSUFFICIENT, reason, null, List.of());
        }

        public static Evaluation needMoreInfo(String reason, String message) {
            return new Evaluation(Verdict.NEED_MORE_INFO, reason, message, List.of());
        }

        public static Evaluation failed(String reason) {
            return new Evaluation(Verdict.EVALUATION_FAILED, reason, null, List.of());
        }
    }

    /** 仅供旧单测/非 Spring 构造兼容；正式运行必须注入独立 evaluator。 */
    static AgentGoalEvaluator trustPlanner() {
        return new AgentGoalEvaluator() {
            @Override
            public Evaluation evaluate(String goal, List<AgentObservation> observations,
                                       List<String> deterministicAnswers, List<Evidence> evidences,
                                       CapabilityInvocationContext context) {
                return Evaluation.satisfied("compatibility evaluator trusts planner decision");
            }

            @Override
            public boolean consumesLlmCall() {
                return false;
            }
        };
    }
}
