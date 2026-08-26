package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通用标量比较能力。
 *
 * <p>用于 DAG 中对上游确定性标量结果做 EQ/NE/GT/GTE/LT/LTE 比较。它不理解“重复/专利/订单”等业务语义，
 * 只负责执行数值关系；该关系是否足以证明 originalGoal 仍由 Goal Evaluator 判断。</p>
 */
@Component
public class ScalarCompareCapability implements KnowledgeCapability {
    public static final String NAME = "scalar_compare";

    @Override
    public CapabilityDefinition definition() {
        return new CapabilityDefinition(NAME, "1",
                "对两个已执行得到的数值标量做确定性比较。只处理 SCALAR，不处理实体集合。"
                        + " structured_query 的标量结果应通过 DAG $ref selector=metadata 传入；"
                        + "本能力会从 metadata.scalarValue 读取数值。",
                Map.of(
                        "left", "必填。数值，或上游 SCALAR 结果的 metadata（必须包含 scalarValue）。",
                        "operator", "必填。EQ / NE / GT / GTE / LT / LTE。",
                        "right", "必填。数值，或上游 SCALAR 结果的 metadata（必须包含 scalarValue）。"
                ),
                Set.of("left", "operator", "right"), "BOOLEAN_SCALAR", true,
                Set.of(), Set.of(), Set.of(), 1_000L, 1);
    }

    @Override
    public CapabilityArgumentValidation validateArguments(CapabilityInvocationContext context,
                                                           Map<String, Object> arguments) {
        BigDecimal left = scalar(arguments == null ? null : arguments.get("left"));
        BigDecimal right = scalar(arguments == null ? null : arguments.get("right"));
        Operator operator = operator(arguments == null ? null : arguments.get("operator"));
        if (left == null) return CapabilityArgumentValidation.invalid("left must be a numeric scalar or metadata.scalarValue");
        if (right == null) return CapabilityArgumentValidation.invalid("right must be a numeric scalar or metadata.scalarValue");
        if (operator == null) return CapabilityArgumentValidation.invalid("operator must be EQ, NE, GT, GTE, LT or LTE");
        return CapabilityArgumentValidation.ok();
    }

    @Override
    public String canonicalExecutionKey(CapabilityInvocationContext context, Map<String, Object> arguments) {
        BigDecimal left = scalar(arguments == null ? null : arguments.get("left"));
        BigDecimal right = scalar(arguments == null ? null : arguments.get("right"));
        Operator operator = operator(arguments == null ? null : arguments.get("operator"));
        if (left == null || right == null || operator == null) return null;
        return "left=" + left.stripTrailingZeros().toPlainString()
                + ";operator=" + operator
                + ";right=" + right.stripTrailingZeros().toPlainString();
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        BigDecimal left = scalar(arguments == null ? null : arguments.get("left"));
        BigDecimal right = scalar(arguments == null ? null : arguments.get("right"));
        Operator operator = operator(arguments == null ? null : arguments.get("operator"));
        if (left == null || right == null || operator == null) {
            return CapabilityResult.failure(AgentStopReason.INVALID_CAPABILITY_CALL,
                    "scalar comparison arguments are invalid");
        }
        int cmp = left.compareTo(right);
        boolean matched = switch (operator) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
        };
        Output output = new Output(left, operator.name(), right, matched);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resultShape", "BOOLEAN_SCALAR");
        metadata.put("leftScalar", left);
        metadata.put("operator", operator.name());
        metadata.put("rightScalar", right);
        metadata.put("booleanValue", matched);
        metadata.put("derivedDeterministic", true);
        metadata.put("outputComplete", true);
        return CapabilityResult.success(output, metadata);
    }

    private BigDecimal scalar(Object raw) {
        if (raw instanceof Number number) {
            try { return new BigDecimal(String.valueOf(number)); }
            catch (NumberFormatException ignore) { return null; }
        }
        if (raw instanceof Map<?, ?> map) {
            Object value = map.get("scalarValue");
            if (value == null) value = map.get("value");
            return scalar(value);
        }
        if (raw == null) return null;
        try { return new BigDecimal(String.valueOf(raw).trim()); }
        catch (NumberFormatException ignore) { return null; }
    }

    private Operator operator(Object raw) {
        if (raw == null) return null;
        try { return Operator.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return null; }
    }

    private enum Operator { EQ, NE, GT, GTE, LT, LTE }

    public record Output(BigDecimal left,
                         String operator,
                         BigDecimal right,
                         boolean matched) implements AgentCapabilityOutput {
        @Override
        public String summary() {
            return "scalar comparison: " + left.stripTrailingZeros().toPlainString() + " " + operator + " "
                    + right.stripTrailingZeros().toPlainString() + " => " + matched;
        }

        @Override
        public String progressHash() {
            return left.stripTrailingZeros().toPlainString() + ":" + operator + ":"
                    + right.stripTrailingZeros().toPlainString() + ":" + matched;
        }

        @Override
        public String deterministicAnswer() {
            return "标量比较结果：" + left.stripTrailingZeros().toPlainString() + " " + operator + " "
                    + right.stripTrailingZeros().toPlainString() + " = " + matched + "。";
        }
    }
}
