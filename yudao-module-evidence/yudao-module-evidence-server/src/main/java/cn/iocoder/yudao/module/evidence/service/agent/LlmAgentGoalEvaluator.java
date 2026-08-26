package cn.iocoder.yudao.module.evidence.service.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 独立 Goal Satisfaction Gate。
 *
 * <p>Planner 负责“下一步做什么”；本组件只负责“现在这些执行事实是否已经完整证明 OriginalGoal”。
 * 这种职责分离避免 Planner 因为自己刚执行了一个相关工具，就对结果充分性产生确认偏差。</p>
 */
@Slf4j
@Component
public class LlmAgentGoalEvaluator implements AgentGoalEvaluator {
    private static final String PROMPT_KEY = "agent-goal-evaluator-v1.2";
    private static final String DEFAULT_PROMPT = """
            你是企业知识 Agent 的独立 Goal Satisfaction Evaluator，不负责回答用户，也不负责选择工具。
            你的唯一任务：判断 accumulated observations 是否已经直接、完整、可靠地证明 immutable originalGoal。
            只输出 JSON，不要 Markdown，不要解释推理过程。

            verdict 只能是 SATISFIED / INSUFFICIENT / NEED_MORE_INFO。

            判定规则：
            1. 只使用提供的 observations、deterministicAnswers、evidenceSnippets 和机器元数据；禁止补充常识、猜测或未执行的计算。
               如果比较/运算已经由确定性 capability 执行，并产生 derivedDeterministic=true 或确定性答案，则该运算结果属于已执行事实。
            2. “相关”不等于“充分”。如果目标要求更强事实，而执行结果只证明较弱事实，必须 INSUFFICIENT。
               例如：某字段存在，只能证明有值；不能证明元素数量、阈值、重复、极值、先后、相似、关系或比较结论。
            3. 证明义务不能强于 originalGoal：
               - 存在/是非型目标（例如“有没有/是否存在/是不是”）只要求可靠证明 true 或 false；若完整计数、分组、阈值或确定性标量比较已经直接证明存在性，不得额外要求列出具体 witness。
               - 枚举型目标（例如“哪些/都有谁/列出来/罗列”）才要求返回具体项，并要求覆盖范围足以支持“全部”语义。
               - 数量型目标要求直接数量；极值/排序型目标要求对应极值/排序执行事实；不要把一种目标擅自升级成另一种更强目标。
            4. 如果目标要求数量、去重数量、分组、阈值、极值、排序、派生值或关系，必须看到执行计划/结果确实计算或检验了对应属性；不能从相邻事实猜测。
               对跨结果的大小/相等关系，优先要求 scalar_compare 等确定性运算已经执行，禁止由 Evaluator 临场做未执行计算。
            5. dataGrain 是证明契约的一部分：SOURCE_RECORD 表示物理记录，LOGICAL_ENTITY 表示按领域身份去重后的逻辑实体。
               不能把不同 grain 的行集合当成同一集合直接分组、去重或枚举；如果结论依赖两个 grain 的数量关系，必须看到各自 grain 明确且比较本身已由确定性能力执行。
            6. 对“全部/总共/有没有任何/最早/最晚/最多/最少/最大/最小/排名/TopN”等全集结论，判断的是计算源覆盖，不是最终返回行数：
               - coverageComplete=true 且 sourceTruncated=false 且 missingValueCount=0，表示计算确实覆盖目标范围；
               - outputLimited=true、limited=true 或 outputComplete=false 只表示最终展示被 limit，不代表计算源不完整；绝不能仅因此判 INSUFFICIENT；
               - coverageComplete=false、sourceTruncated=true、missingValueCount>0 或 source failure 才表示全集证明不足。
               - 兼容旧 observation：若没有 coverageComplete，但 completeDataset=true 且 sourceTruncated 不为 true 且 missingValueCount=0，也应视为源覆盖完整。
            7. authoritativeEmpty=true 只有在该结构化查询与 originalGoal 的目标条件直接一致时，才能证明“没有/为0”。普通语义检索 NO_MATCHES 不能证明全集不存在。
            8. 语义检索证据必须实际包含回答 originalGoal 所需事实；候选相似、关键词相关或命中数量本身不能替代目标事实。
            9. 如果 originalGoal 本身存在会改变答案含义的关键歧义，而且 observations 没有消除歧义，返回 NEED_MORE_INFO，并给出简短 clarificationMessage。
            10. 如果所有必要部分都已经被直接执行事实覆盖，返回 SATISFIED；否则返回 INSUFFICIENT，并在 reason 中指出“还缺哪类证明”，不要建议具体业务硬编码。

            输出格式：
            {"verdict":"SATISFIED|INSUFFICIENT|NEED_MORE_INFO","reason":"简短理由","clarificationMessage":null}
            """;

    private final ModelApi modelApi;
    private final PromptSupport promptSupport;

    public LlmAgentGoalEvaluator(ModelApi modelApi, PromptSupport promptSupport) {
        this.modelApi = modelApi;
        this.promptSupport = promptSupport;
    }

    @Override
    public Evaluation evaluate(String originalGoal,
                               List<AgentObservation> observations,
                               List<String> deterministicAnswers,
                               List<Evidence> evidences,
                               CapabilityInvocationContext context) {
        if (StrUtil.isBlank(originalGoal)) return Evaluation.failed("original goal is blank");
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get(PROMPT_KEY, DEFAULT_PROMPT));
            req.setUser(buildInput(originalGoal, observations, deterministicAnswers, evidences));
            req.setTemperature(0D);
            req.setScenario(PROMPT_KEY);
            req.setTraceId(context == null ? null : context.traceId());
            CommonResult<String> response = modelApi.chat(req);
            JSONObject json = parseJson(response == null ? null : response.getCheckedData());
            if (json == null) return Evaluation.failed("goal evaluator did not return valid JSON");
            Verdict verdict = verdict(json.getStr("verdict"));
            if (verdict == null || verdict == Verdict.EVALUATION_FAILED) {
                return Evaluation.failed("goal evaluator returned invalid verdict");
            }
            String reason = StrUtil.blankToDefault(json.getStr("reason"), "goal coverage evaluation completed");
            if (verdict == Verdict.NEED_MORE_INFO) {
                return Evaluation.needMoreInfo(reason,
                        StrUtil.blankToDefault(json.getStr("clarificationMessage"), "请补充问题中关键但未明确的信息。"));
            }
            return verdict == Verdict.SATISFIED
                    ? Evaluation.satisfied(reason)
                    : Evaluation.insufficient(reason);
        } catch (Exception e) {
            log.warn("[{}][failed traceId={} error={}]", PROMPT_KEY,
                    context == null ? null : context.traceId(), e.getMessage());
            return Evaluation.failed("goal evaluator unavailable");
        }
    }

    private String buildInput(String originalGoal,
                              List<AgentObservation> observations,
                              List<String> deterministicAnswers,
                              List<Evidence> evidences) {
        StringBuilder sb = new StringBuilder(12_000);
        sb.append("originalGoal=").append(originalGoal).append('\n');
        sb.append("observations=").append(JSONUtil.toJsonStr(
                observations == null ? List.of() : observations)).append('\n');
        sb.append("deterministicAnswers=").append(JSONUtil.toJsonStr(
                deterministicAnswers == null ? List.of() : deterministicAnswers)).append('\n');
        sb.append("evidenceSnippets=").append(JSONUtil.toJsonStr(evidenceSnippets(evidences))).append('\n');
        return sb.toString();
    }

    private List<String> evidenceSnippets(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Evidence e : evidences) {
            if (e == null) continue;
            out.add("doc=" + e.getDocumentId()
                    + ",name=" + StrUtil.maxLength(StrUtil.nullToEmpty(e.getDocumentName()), 100)
                    + ",score=" + e.getScore()
                    + ",text=" + StrUtil.maxLength(StrUtil.nullToEmpty(e.getContent()).replace('\n', ' '), 260));
            if (out.size() >= 10) break;
        }
        return List.copyOf(out);
    }

    private JSONObject parseJson(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        try {
            int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
            return start >= 0 && end > start ? JSONUtil.parseObj(raw.substring(start, end + 1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Verdict verdict(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        try { return Verdict.valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return null; }
    }
}
