package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionState;
import cn.iocoder.yudao.module.evidence.service.agent.AgentObservation;
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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** LLM Query Planner that produces a typed bounded DAG instead of a business intent enum. */
@Slf4j
@Component
public class LlmAgentExecutionPlanner implements AgentExecutionPlanner {
    private static final String PROMPT_KEY = "agent-execution-plan-v1";
    private static final String DEFAULT_PROMPT = """
            你是企业知识平台的 Query Planner。你的唯一目标是围绕 immutable originalGoal 生成可执行计划，
            不是给问题分类，也不是直接编造答案。系统会给你当前真实 capabilities、domainFields、domainMetrics、
            observations、references 和预算。

            只输出 JSON，不要 Markdown，不要输出内部推理过程。

            action 只能是 EXECUTE_PLAN / ANSWER / NEED_MORE_INFO / STOP。

            当 action=EXECUTE_PLAN 时，输出：
            {"action":"EXECUTE_PLAN","nodes":[
              {"id":"n1","capability":"<capability>","arguments":{},"purpose":"本节点证明什么","dependsOn":[]},
              {"id":"n2","capability":"<capability>","arguments":{},"purpose":"本节点证明什么","dependsOn":["n1"]}
            ],"message":null}

            DAG 规则：
            1. 节点只能调用 capabilities 中真实存在的能力；arguments 必须符合 argumentSchema。
            2. tenantId/userId/kbId/domainCode/traceId/permission/budget 等系统范围禁止写入 arguments。
            3. 可以独立执行的事实必须放在无依赖节点中，让 Runtime 并行执行；有真实数据依赖才写 dependsOn。
            4. 下游需要消费上游结果时，只能使用显式引用：
               {"$ref":"n1","selector":"verifiedEntityIds"}。
               selector 只允许 data / metadata / status / verifiedEntityIds / deterministicAnswer / evidences / summary，
               且引用节点必须同时出现在 dependsOn。
            5. originalGoal 不可改写成更弱问题。字段存在性不能证明数量、极值、关系、阈值等更强事实。
            6. 结构化事实严格依据 domainFields/domainMetrics；禁止编造字段、指标、transform、operator。
            7. 精确查询/过滤/聚合优先走结构化能力；字面包含走全文/精确文本能力；开放语义问题才走 semantic retrieval。
               不要把所有问题都变成向量检索。
            8. completeDataset=true + authoritativeEmpty=true 才能作为完整范围的“没有/为0”证明。
               semantic retrieval 的 NO_MATCHES 不是全集不存在证明。
            9. 如果 observations 中出现 goal_evaluator + GOAL_NOT_SATISFIED，新的计划必须直接补足指出的证明缺口，
               不能换 JSON 写法重复同一个弱事实。
            10. VALIDATION 类错误可以通过改变计划修正；PERMISSION/CONFIGURATION/DEPENDENCY/DATA_INCOMPLETE
                不能通过原样重试绕过；TIMEOUT/THROTTLED/TRANSIENT 的原样重试由 Runtime 自己处理。
            11. 节点数量不得超过 maxPlanNodes。不要为了“保险”重复查询。
            12. 如果现有 references 已经足以回答 originalGoal，action=ANSWER；如果必须由用户补充关键信息，
                action=NEED_MORE_INFO；如果当前能力/数据边界无法完成，action=STOP。
            """;

    private final ModelApi modelApi;
    private final PromptSupport promptSupport;
    private final CapabilityRegistry capabilityRegistry;
    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;

    public LlmAgentExecutionPlanner(ModelApi modelApi,
                                    PromptSupport promptSupport,
                                    CapabilityRegistry capabilityRegistry,
                                    DomainFieldRegistry fieldRegistry,
                                    DomainMetricRegistry metricRegistry) {
        this.modelApi = modelApi;
        this.promptSupport = promptSupport;
        this.capabilityRegistry = capabilityRegistry;
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
    }

    @Override
    public AgentPlanningDecision plan(AgentExecutionState state,
                                      CapabilityInvocationContext context,
                                      List<AgentObservation> observations,
                                      List<ReferenceRecord> references,
                                      List<ChatTurnDTO> history,
                                      int replanAttempt,
                                      int maxPlanNodes) {
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get(PROMPT_KEY, DEFAULT_PROMPT));
            req.setUser(buildInput(state, context, observations, references, history, replanAttempt, maxPlanNodes));
            req.setTemperature(0D);
            req.setScenario(PROMPT_KEY);
            req.setTraceId(context == null ? null : context.traceId());
            CommonResult<String> response = modelApi.chat(req);
            JSONObject json = parseJson(response == null ? null : response.getCheckedData());
            if (json == null) return AgentPlanningDecision.stop("规划器未返回合法 JSON。");

            String action = StrUtil.blankToDefault(json.getStr("action"), "STOP").trim().toUpperCase();
            return switch (action) {
                case "EXECUTE_PLAN" -> parsePlan(json, state, context, replanAttempt, maxPlanNodes);
                case "ANSWER" -> AgentPlanningDecision.answer();
                case "NEED_MORE_INFO" -> AgentPlanningDecision.needInfo(
                        StrUtil.blankToDefault(json.getStr("message"), "请补充完成查询所需的关键信息。"));
                case "STOP" -> AgentPlanningDecision.stop(
                        StrUtil.blankToDefault(json.getStr("message"), "当前能力不足以可靠完成该问题。"));
                default -> AgentPlanningDecision.stop("规划器返回了非法 action。");
            };
        } catch (Exception e) {
            log.warn("[{}][failed traceId={} error={}]", PROMPT_KEY,
                    context == null ? null : context.traceId(), e.getMessage());
            return AgentPlanningDecision.stop("规划服务暂不可用。");
        }
    }

    private AgentPlanningDecision parsePlan(JSONObject json,
                                            AgentExecutionState state,
                                            CapabilityInvocationContext context,
                                            int replanAttempt,
                                            int maxPlanNodes) {
        JSONArray array = json.getJSONArray("nodes");
        if (array == null || array.isEmpty()) return AgentPlanningDecision.stop("规划器没有返回执行节点。");
        if (array.size() > Math.max(1, maxPlanNodes)) {
            return AgentPlanningDecision.stop("规划器返回的节点数量超过剩余执行预算。");
        }
        List<PlanNode> nodes = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object raw : array) {
            JSONObject nodeJson = raw instanceof JSONObject obj ? obj : JSONUtil.parseObj(raw);
            String id = nodeJson.getStr("id");
            String capability = nodeJson.getStr("capability");
            if (StrUtil.isBlank(id) || !ids.add(id)) return AgentPlanningDecision.stop("规划器返回了空或重复 node id。");
            if (StrUtil.isBlank(capability) || capabilityRegistry.getVisible(capability, context) == null) {
                return AgentPlanningDecision.stop("规划器选择了当前不可用 capability: " + capability);
            }
            Map<String, Object> arguments = objectMap(nodeJson.getJSONObject("arguments"));
            Set<String> dependsOn = stringSet(nodeJson.getJSONArray("dependsOn"));
            nodes.add(new PlanNode(id, capability, arguments, nodeJson.getStr("purpose"), dependsOn));
        }
        String trace = context == null ? "plan" : StrUtil.blankToDefault(context.traceId(), "plan");
        String planId = trace + "-r" + Math.max(0, replanAttempt);
        return AgentPlanningDecision.execute(new AgentExecutionPlan(
                planId, state.getOriginalGoal(), Math.max(0, replanAttempt), nodes));
    }

    private String buildInput(AgentExecutionState state,
                              CapabilityInvocationContext context,
                              List<AgentObservation> observations,
                              List<ReferenceRecord> references,
                              List<ChatTurnDTO> history,
                              int replanAttempt,
                              int maxPlanNodes) {
        String domain = context == null ? null : context.domainCode();
        StringBuilder sb = new StringBuilder(16_000);
        sb.append("originalGoal=").append(state.getOriginalGoal()).append('\n');
        sb.append("currentSubGoal=").append(StrUtil.nullToEmpty(state.getCurrentSubGoal())).append('\n');
        sb.append("replanAttempt=").append(replanAttempt).append('\n');
        sb.append("maxPlanNodes=").append(Math.max(1, maxPlanNodes)).append('\n');
        sb.append("capabilities=").append(JSONUtil.toJsonStr(capabilityRegistry.listDefinitions(context))).append('\n');
        sb.append("domainFields=").append(JSONUtil.toJsonStr(fieldSchema(domain))).append('\n');
        sb.append("domainMetrics=").append(JSONUtil.toJsonStr(metricSchema(domain))).append('\n');
        sb.append("verifiedContextEntityIds=")
                .append(context == null ? List.of() : context.contextEntityIds()).append('\n');
        sb.append("history=").append(JSONUtil.toJsonStr(historySummary(history))).append('\n');
        sb.append("observations=").append(JSONUtil.toJsonStr(observations == null ? List.of() : observations)).append('\n');
        sb.append("references=").append(JSONUtil.toJsonStr(referenceSummary(references))).append('\n');
        return sb.toString();
    }

    private List<Map<String, Object>> referenceSummary(List<ReferenceRecord> references) {
        if (references == null || references.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReferenceRecord reference : references) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("referenceId", reference.referenceId());
            item.put("nodeId", reference.nodeId());
            item.put("capability", reference.capability());
            item.put("status", reference.status());
            item.put("summary", StrUtil.maxLength(reference.summary(), 800));
            item.put("deterministicAnswer", StrUtil.maxLength(reference.deterministicAnswer(), 500));
            item.put("verifiedEntityIds", reference.verifiedEntityIds());
            item.put("metadata", reference.metadata());
            out.add(item);
        }
        return out;
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

    private Map<String, Object> objectMap(JSONObject object) {
        if (object == null || object.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : object.keySet()) out.put(key, object.get(key));
        return out;
    }

    private Set<String> stringSet(JSONArray array) {
        if (array == null || array.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object value : array) if (value != null && StrUtil.isNotBlank(String.valueOf(value))) out.add(String.valueOf(value));
        return out;
    }

    private JSONObject parseJson(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            return start >= 0 && end > start ? JSONUtil.parseObj(raw.substring(start, end + 1)) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
