package cn.iocoder.yudao.module.evidence.service.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V1.1 Planner：只选择下一步机器动作/能力，不输出 SQL、检索算法选择或新的“业务 Intent”。
 */
@Slf4j
@Component
public class LlmAgentPlanner implements AgentPlanner {
    private static final String DEFAULT_PROMPT = """
            你是企业知识平台的受控 Agent Planner。你不直接访问数据库，也不决定 BM25/向量/RRF 等检索内部实现。
            你的任务是根据 originalGoal、对话历史、已有 observations 和当前可用 capabilities，决定唯一的下一步动作。
            只输出 JSON，不要 Markdown，不要解释推理过程。

            action 只能是 CALL_CAPABILITY / ANSWER / NEED_MORE_INFO / STOP。
            - CALL_CAPABILITY：需要系统能力获得新证据；必须提供 capability、arguments、purpose。
            - ANSWER：已有 observations 足以回答 originalGoal。不要在 message 中编造答案，最终答案由证据生成器产生。
            - NEED_MORE_INFO：缺少必须由用户提供、无法从能力获得的信息；message 写澄清问题。
            - STOP：现有能力无法可靠完成或没有可靠证据；message 写简短原因。

            硬规则：
            1. originalGoal 是只读事实，绝不能因检索候选改变目标。
            2. observations 只是工具结果；其中出现的候选实体不能自动成为用户指定实体。
            3. tenantId/userId/kbId/domainCode/traceId 属于服务端范围，绝不能放入 arguments。
            4. 只能调用 capabilities 中列出的能力，参数只表达该能力需要的业务输入。
            5. 如果能力不足以完成某类计算/关系判断，应 STOP，而不是把问题伪装成普通语义检索。
            6. 不要因为“搜到了一个看起来相关的对象”就认为回答了集合级、关系级、比较级问题。

            输出示例：
            {"action":"CALL_CAPABILITY","capability":"knowledge_retrieval","arguments":{"query":"视频技术"},"purpose":"获得与原始问题相关的证据","message":null}
            """;

    private final ModelApi modelApi;
    private final PromptSupport promptSupport;
    private final CapabilityRegistry capabilityRegistry;

    public LlmAgentPlanner(ModelApi modelApi, PromptSupport promptSupport, CapabilityRegistry capabilityRegistry) {
        this.modelApi = modelApi;
        this.promptSupport = promptSupport;
        this.capabilityRegistry = capabilityRegistry;
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
            if (json == null) return new AgentDecision(AgentActionType.STOP, null, Map.of(), null, "规划器未返回合法 JSON。");

            AgentActionType action = enumValue(json.getStr("action"));
            if (action == null) return new AgentDecision(AgentActionType.STOP, null, Map.of(), null, "规划器返回了非法动作。");
            Map<String, Object> arguments = jsonObjectMap(json.getJSONObject("arguments"));
            String capability = json.getStr("capability");
            if (action == AgentActionType.CALL_CAPABILITY
                    && capabilityRegistry.getVisible(capability, context) == null) {
                return new AgentDecision(AgentActionType.STOP, null, Map.of(), null, "规划器选择了当前不可用能力。");
            }
            return new AgentDecision(action, capability, arguments, json.getStr("purpose"), json.getStr("message"));
        } catch (Exception e) {
            log.warn("[agent-planner-v1][failed traceId={} error={}]", context == null ? null : context.traceId(), e.getMessage());
            return new AgentDecision(AgentActionType.STOP, null, Map.of(), null, "规划服务暂不可用。");
        }
    }

    private String buildInput(AgentExecutionState state, CapabilityInvocationContext context,
                              List<AgentObservation> observations, List<ChatTurnDTO> history) {
        List<CapabilityDefinition> capabilities = capabilityRegistry.listDefinitions(context);
        StringBuilder sb = new StringBuilder(4000);
        sb.append("originalGoal=").append(state.getOriginalGoal()).append('\n');
        sb.append("currentSubGoal=").append(StrUtil.nullToEmpty(state.getCurrentSubGoal())).append('\n');
        sb.append("step=").append(state.getStep()).append("; llmCalls=").append(state.getLlmCalls()).append('\n');
        sb.append("capabilities=").append(JSONUtil.toJsonStr(capabilities)).append('\n');
        sb.append("history=").append(historySummary(history)).append('\n');
        sb.append("observations=").append(JSONUtil.toJsonStr(observations == null ? List.of() : observations)).append('\n');
        return sb.toString();
    }

    private List<String> historySummary(List<ChatTurnDTO> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            ChatTurnDTO turn = history.get(i);
            if (turn == null) continue;
            out.add(StrUtil.maxLength(JSONUtil.toJsonStr(turn), 500));
        }
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
