package cn.iocoder.yudao.module.evidence.service.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** V1.1 Planner：理解开放用户目标，严格从运行时能力契约中选择/组合下一步。 */
@Slf4j
@Component
public class LlmAgentPlanner implements AgentPlanner {
    private static final String PROMPT_KEY = "agent-planner-v1.1-pipeline";
    private static final String DEFAULT_PROMPT = """
            你是企业知识平台的受控 Agent Planner。你的职责不是给用户意图分类，而是完成 originalGoal。
            你只能观察系统提供的 capabilities、domainFields、domainMetrics、history 和 observations，选择唯一下一步动作。
            只输出 JSON，不要 Markdown，不要解释内部推理过程。

            action 只能是 CALL_CAPABILITY / ANSWER / NEED_MORE_INFO / STOP。
            CALL_CAPABILITY 必须给 capability、arguments、purpose；arguments 必须严格符合该 capability 的 argumentSchema。

            不可违反：
            1. originalGoal 只读。工具结果、候选、currentSubGoal 都不能替换或缩窄 originalGoal。
            2. capabilities 是当前运行时真实能力清单；不得调用未列出的能力，不得凭经验假设系统会做某事。
            3. domainFields/domainMetrics 是结构化数据 Source of Truth。字段 code、metric code、operator、transform、sortable/groupable/multiValue 必须以 Schema 为准，禁止编造。
            4. 结构化事实采用闭世界语义：如果 originalGoal 中的业务概念没有对应的已注册字段/指标/安全变换，或存在多个合理映射且选择其中一个会改变问题含义，必须 NEED_MORE_INFO 或 STOP；不得为了能回答而偷偷替换成“最接近”的字段。
            5. tenantId/userId/kbId/domainCode/traceId/permissions/environment/contextEntityIds/timeout/maxRows 等系统范围绝不能写入 arguments。
            6. 如果一个字段 multiValue=true，而问题讨论单个元素、不同元素、按元素分组/聚合，应使用能力契约提供的 explode/展开语义；不要把整段多值字符串当一个值。
            7. 如果问题需要字段派生值（例如日期年份、字符串长度、人名姓氏），只能使用该字段 allowedTransforms 中明确声明的变换；没有声明则能力不足，不得让模型自己算成事实。
            8. observations.status=ERROR 且 recoverableError=true 时，可根据错误 metadata 和 capability contract 修正参数后再调用；如果 errorKind=EQUIVALENT_PLAN，说明该执行语义已经执行过，必须改变真正的 operator/transform/filter/aggregate/orderBy，或基于已有观察 ANSWER/NEED_MORE_INFO/STOP，禁止只改 JSON 写法再次调用。
            9. observations.completeDataset=true 且 authoritativeEmpty=true 表示可信完整范围内的权威空结果。如果这一步直接回答 originalGoal，应 ANSWER“未找到/为0”，不要换一种检索方式猜结果。
            10. observations 已足够回答 originalGoal 时立即 ANSWER。不要为“更确定”而重复同一能力。
            11. 语义检索候选只能作为证据/观察，不能自动变成用户指定实体。只有服务端已验证的上下文实体集合才能成为后续硬范围。
            12. 能力不足、数据完整性不足或安全边界不允许时 STOP/NEED_MORE_INFO，不得伪造答案。
            13. 优先选择能直接、确定性回答目标的最小能力组合；复杂问题可以多步，但每一步 purpose 必须说明它补足 originalGoal 的哪一部分。
            14. 每个 CALL_CAPABILITY 的执行结果必须在逻辑上足以证明 purpose 所要求的事实，不得用“相关但更弱”的事实替代目标事实。若目标要求某个派生属性、数量关系、阈值、极值或对象间关系，计划必须直接计算或检验该属性/关系；字段存在性只能证明“有值”，不能证明元素数量、大小、重复、先后或其它更强结论。
            15. 如果已有 observation 只证明了较弱事实，下一步应改变实际计算语义去补足缺口；不要围绕同一个弱事实换参数形式反复查询。
            16. knowledge_retrieval 的 variants 只表示同一个信息需求的同义表达。若当前信息需求包含多个可以独立检索、共同组成答案的事实缺口，应优先在同一次 knowledge_retrieval 调用中使用 focused subqueries 分解，让运行时并行执行并合并证据；不要在主循环里把独立子问题串行改词碰撞。
            17. semantic retrieval 的 retrievalOutcome=NO_MATCHES 仅表示本次 top-K 证据检索没有取得可用证据，不是对知识库全集“不存在”的证明。只有与目标条件直接一致、completeDataset=true 且 authoritativeEmpty=true 的确定性结果才可以证明“没有/为0”。
            18. outputComplete=false 的结果可以作为局部事实证据，但不能直接支持需要全集完备性的结论，例如总数、不同值总数、列出全部、确认全集不存在。若用户问的是 cardinality/“几个/多少种”，优先使用直接 COUNT/COUNT_DISTINCT 等聚合，而不是 LIST/DISTINCT + limit 后再数输出行。
            19. capability/source failure 且 recoverableError=false 时不得通过换参数或换近似查询绕过；只有明确标记 recoverable 的参数或执行契约错误才允许在预算内自修复。基础设施失败和“合法零匹配”必须严格区分。
            20. 如果 observation 来自 goal_evaluator 且 errorKind=GOAL_NOT_SATISFIED，它表示独立充分性门认为现有事实没有完整证明 originalGoal。下一步必须补齐 evaluator 指出的证明缺口、澄清歧义或 STOP；禁止在证据不变时再次 ANSWER。

            输出格式：
            {"action":"CALL_CAPABILITY","capability":"<capability-name>","arguments":{},"purpose":"本步要补足的信息","message":null}
            或 {"action":"ANSWER","capability":null,"arguments":{},"purpose":"现有观察已足够回答原始问题","message":null}
            """;

    private final ModelApi modelApi;
    private final PromptSupport promptSupport;
    private final CapabilityRegistry capabilityRegistry;
    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;

    @Autowired
    public LlmAgentPlanner(ModelApi modelApi, PromptSupport promptSupport, CapabilityRegistry capabilityRegistry,
                           DomainFieldRegistry fieldRegistry, DomainMetricRegistry metricRegistry) {
        this.modelApi = modelApi;
        this.promptSupport = promptSupport;
        this.capabilityRegistry = capabilityRegistry;
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
    }

    public LlmAgentPlanner(ModelApi modelApi, PromptSupport promptSupport, CapabilityRegistry capabilityRegistry) {
        this(modelApi, promptSupport, capabilityRegistry, null, null);
    }

    @Override
    public AgentDecision decide(AgentExecutionState state, CapabilityInvocationContext context,
                                List<AgentObservation> observations, List<ChatTurnDTO> history) {
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get(PROMPT_KEY, DEFAULT_PROMPT));
            req.setUser(buildInput(state, context, observations, history));
            req.setTemperature(0D);
            req.setScenario(PROMPT_KEY);
            req.setTraceId(context == null ? null : context.traceId());
            CommonResult<String> response = modelApi.chat(req);
            JSONObject json = parseJson(response == null ? null : response.getCheckedData());
            if (json == null) return stop("规划器未返回合法 JSON。");
            AgentActionType action = enumValue(json.getStr("action"));
            if (action == null) return stop("规划器返回了非法动作。");
            Map<String, Object> arguments = jsonObjectMap(json.getJSONObject("arguments"));
            String capability = json.getStr("capability");
            if (action == AgentActionType.CALL_CAPABILITY && capabilityRegistry.getVisible(capability, context) == null) {
                return stop("规划器选择了当前不可用能力。");
            }
            return new AgentDecision(action, capability, arguments, json.getStr("purpose"), json.getStr("message"));
        } catch (Exception e) {
            log.warn("[{}][failed traceId={} error={}]", PROMPT_KEY,
                    context == null ? null : context.traceId(), e.getMessage());
            return stop("规划服务暂不可用。");
        }
    }

    private AgentDecision stop(String message) {
        return new AgentDecision(AgentActionType.STOP, null, Map.of(), null, message);
    }

    private String buildInput(AgentExecutionState state, CapabilityInvocationContext context,
                              List<AgentObservation> observations, List<ChatTurnDTO> history) {
        List<CapabilityDefinition> capabilities = capabilityRegistry.listDefinitions(context);
        String domain = context == null ? null : context.domainCode();
        StringBuilder sb = new StringBuilder(12_000);
        sb.append("originalGoal=").append(state.getOriginalGoal()).append('\n');
        sb.append("currentSubGoal=").append(StrUtil.nullToEmpty(state.getCurrentSubGoal())).append('\n');
        sb.append("step=").append(state.getStep()).append("; llmCalls=").append(state.getLlmCalls()).append('\n');
        sb.append("capabilities=").append(JSONUtil.toJsonStr(capabilities)).append('\n');
        sb.append("domainFields=").append(JSONUtil.toJsonStr(fieldSchema(domain))).append('\n');
        sb.append("domainMetrics=").append(JSONUtil.toJsonStr(metricSchema(domain))).append('\n');
        sb.append("conversationContextEntityIds=")
                .append(context == null ? List.of() : context.contextEntityIds()).append('\n');
        sb.append("history=").append(historySummary(history)).append('\n');
        sb.append("observations=").append(JSONUtil.toJsonStr(observations == null ? List.of() : observations)).append('\n');
        return sb.toString();
    }

    private List<Map<String, Object>> fieldSchema(String domainCode) {
        if (fieldRegistry == null || StrUtil.isBlank(domainCode)) return List.of();
        List<FieldDefinition> fields = new ArrayList<>(fieldRegistry.all(domainCode));
        fields.sort(Comparator.comparing(FieldDefinition::getFieldCode));
        List<Map<String, Object>> out = new ArrayList<>();
        for (FieldDefinition field : fields) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", field.getFieldCode());
            item.put("aliases", field.getAliases());
            item.put("entityType", field.getEntityType());
            item.put("valueType", field.getValueType());
            item.put("multiValue", field.isMultiValue());
            item.put("filterable", field.isFilterable());
            item.put("operators", field.getAllowedOperators());
            item.put("sortable", field.isSortable());
            item.put("groupable", field.isGroupable());
            item.put("allowedTransforms", field.getAllowedTransforms());
            item.put("exactIdentifier", field.isExactIdentifier());
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> metricSchema(String domainCode) {
        if (metricRegistry == null || StrUtil.isBlank(domainCode)) return List.of();
        List<MetricDefinition> metrics = new ArrayList<>(metricRegistry.all(domainCode));
        metrics.sort(Comparator.comparing(MetricDefinition::getMetricCode));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetricDefinition metric : metrics) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", metric.getMetricCode());
            item.put("aliases", metric.getAliases());
            item.put("entityType", metric.getEntityType());
            item.put("valueType", metric.getValueType());
            item.put("operations", metric.getSupportedOperations());
            item.put("displayName", metric.getDisplayName());
            item.put("unit", metric.getUnit());
            item.put("description", metric.getDescription());
            out.add(item);
        }
        return out;
    }

    private List<String> historySummary(List<ChatTurnDTO> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            ChatTurnDTO turn = history.get(i);
            if (turn != null) out.add(StrUtil.maxLength(JSONUtil.toJsonStr(turn), 500));
        }
        return out;
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

    private AgentActionType enumValue(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        try { return AgentActionType.valueOf(raw.trim().toUpperCase()); }
        catch (Exception e) { return null; }
    }

    private Map<String, Object> jsonObjectMap(JSONObject json) {
        if (json == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        json.forEach(out::put);
        return out;
    }
}
