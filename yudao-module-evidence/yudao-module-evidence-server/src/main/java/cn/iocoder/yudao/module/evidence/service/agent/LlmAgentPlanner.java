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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** V1.1 Planner：只选择下一步机器动作/能力，不输出 SQL、检索算法选择或新的业务 Intent。 */
@Slf4j
@Component
public class LlmAgentPlanner implements AgentPlanner {
    private static final String DEFAULT_PROMPT = """
            你是企业知识平台的受控 Agent Planner。你不直接访问数据库，也不决定 BM25/向量/RRF 等检索内部实现。
            你的任务是根据 originalGoal、对话历史、已有 observations、Domain Schema 和当前可用 capabilities，决定唯一的下一步动作。
            只输出 JSON，不要 Markdown，不要解释推理过程。

            action 只能是 CALL_CAPABILITY / ANSWER / NEED_MORE_INFO / STOP。
            CALL_CAPABILITY 必须提供 capability、arguments、purpose；ANSWER 只表示证据已足够，最终回答由系统生成/确定性能力返回。

            硬规则：
            1. originalGoal 永远只读，不能因候选、工具结果、二次搜索而改变。
            2. observations 是工具结果。语义检索候选不能自动成为用户指定实体；只有 structured_query 返回的 verifiedEntityIds 或服务端 conversationContextEntityIds 才能成为 trusted scope。
            3. tenantId/userId/kbId/domainCode/traceId/permissions/environment 绝不能放入 arguments。
            4. 只能调用 capabilities 中列出的能力，严格遵守 argumentSchema。
            5. 字段/指标必须来自 domainFields/domainMetrics，禁止编造 code。
            6. 精确事实、计数、聚合、字段投影优先 structured_query；明确逐字原文要求使用 exact_text_search；开放语义事实使用 knowledge_retrieval。
            7. 用户明确说“它/这个/刚才那个/这些”并且 conversationContextEntityIds 非空时，检索能力 scope=CONTEXT；没有可信上下文对象时 NEED_MORE_INFO。
            8. PATENT 领域询问某一权利要求的原文、引用、依赖或从属关系时：先确保 conversationContextEntityIds 中只有一个可信专利对象，再调用 patent_claim_lookup；不要让普通 RAG 猜 claim 依赖关系。
            9. 集合级相似字段关系优先 similar_field_values；不要用普通语义 TopK 冒充全集结论。
            10. 一个问题同时包含确定性字段事实和语义解释时可以多步调用能力：先 structured_query 建立 trusted scope，再在 scope=CONTEXT 下检索剩余语义证据，最后 ANSWER。
            11. observations 已足够回答 originalGoal 时必须 ANSWER，不要重复调用相同能力。
            12. 能力不足以完成问题时 STOP，不得伪造答案。

            JSON: {"action":"CALL_CAPABILITY","capability":"knowledge_retrieval","arguments":{"query":"视频技术"},"purpose":"获得与原始问题相关的证据","message":null}
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
            req.setSystem(promptSupport.get("agent-planner-v1", DEFAULT_PROMPT));
            req.setUser(buildInput(state, context, observations, history));
            req.setTemperature(0D);
            req.setScenario("agent-planner-v1");
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
            log.warn("[agent-planner-v1][failed traceId={} error={}]", context == null ? null : context.traceId(), e.getMessage());
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
        StringBuilder sb = new StringBuilder(8000);
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
        List<Map<String, Object>> out = new ArrayList<>();
        for (FieldDefinition field : fieldRegistry.all(domainCode)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", field.getFieldCode());
            item.put("aliases", field.getAliases());
            item.put("valueType", field.getValueType());
            item.put("filterable", field.isFilterable());
            item.put("operators", field.getAllowedOperators());
            item.put("exactIdentifier", field.isExactIdentifier());
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> metricSchema(String domainCode) {
        if (metricRegistry == null || StrUtil.isBlank(domainCode)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetricDefinition metric : metricRegistry.all(domainCode)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", metric.getMetricCode());
            item.put("aliases", metric.getAliases());
            item.put("operations", metric.getSupportedOperations());
            item.put("displayName", metric.getDisplayName());
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
