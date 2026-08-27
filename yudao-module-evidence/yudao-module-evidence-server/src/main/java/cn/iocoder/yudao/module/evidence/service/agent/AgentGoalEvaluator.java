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
     * supportingReferenceIds 是“完整证明集 / proof frontier”；answerReferenceIds 是其用户答案内容子集。
     *
     * <p>例如近似名称解析可以需要 semantic retrieval + structured detail 两条 Reference 才能证明“对象找对了且详情可靠”，
     * 但用户最终展示的详情只应该消费 structured detail。这样支持性语义证据仍参与严格验收，却不会污染答案内容或强制触发
     * 不必要的生成模型调用。</p>
     */
    record Evaluation(Verdict verdict,
                      String reason,
                      String message,
                      List<String> supportingReferenceIds,
                      List<String> answerReferenceIds) {
        public Evaluation {
            supportingReferenceIds = immutableUnique(supportingReferenceIds);
            answerReferenceIds = immutableUnique(answerReferenceIds);
        }

        /** 兼容旧单测/实现：未显式区分角色时，全部证明 Reference 同时作为答案内容 Reference。 */
        public Evaluation(Verdict verdict, String reason, String message, List<String> supportingReferenceIds) {
            this(verdict, reason, message, supportingReferenceIds, supportingReferenceIds);
        }

        /** 兼容更旧的三参数构造。 */
        public Evaluation(Verdict verdict, String reason, String message) {
            this(verdict, reason, message, List.of(), List.of());
        }

        public static Evaluation satisfied(String reason) {
            return new Evaluation(Verdict.SATISFIED, reason, null, List.of(), List.of());
        }

        public static Evaluation satisfied(String reason, List<String> supportingReferenceIds) {
            return new Evaluation(Verdict.SATISFIED, reason, null,
                    supportingReferenceIds, supportingReferenceIds);
        }

        public static Evaluation satisfied(String reason,
                                           List<String> supportingReferenceIds,
                                           List<String> answerReferenceIds) {
            return new Evaluation(Verdict.SATISFIED, reason, null,
                    supportingReferenceIds, answerReferenceIds);
        }

        public static Evaluation insufficient(String reason) {
            return new Evaluation(Verdict.INSUFFICIENT, reason, null, List.of(), List.of());
        }

        public static Evaluation needMoreInfo(String reason, String message) {
            return new Evaluation(Verdict.NEED_MORE_INFO, reason, message, List.of(), List.of());
        }

        public static Evaluation failed(String reason) {
            return new Evaluation(Verdict.EVALUATION_FAILED, reason, null, List.of(), List.of());
        }

        private static List<String> immutableUnique(List<String> source) {
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            if (source != null) {
                for (String value : source) {
                    if (value != null && !value.isBlank()) unique.add(value.trim());
                }
            }
            return List.copyOf(new ArrayList<>(unique));
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
