package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.knowledge.api.dto.IntentDTO;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.retrieval.service.prompt.PromptSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询语义理解/改写/拆解(LLM; 失败降级仅返回原句)
 * <p>
 * Task 2 多轮消歧: 提示词融入历史对话(指代展开 + 实体/产品继承);
 * LLM 失败且带历史时规则兜底合并最近用户消息与当前问题为改写, 保证降级也有基本消歧召回。
 * <p>
 * Task 3 动态意图: 传入知识库意图集时, 意图段替换为知识库意图列表(不匹配钳制 OUT_OF_SCOPE);
 * 未传意图集时保持固定 6 枚举(WARRANTY/REFUND/LOGISTICS/REPAIR/PRICE/OTHER), 完全兼容旧行为。
 */
@Slf4j
@Service
public class QueryAnalysisService {

    private static final String SYSTEM_PROMPT = """
            你是企业客服知识库的"查询分析器"。给定客户问题(可能附带历史对话), 输出 JSON:
            {"intent": "意图分类(WARRANTY/REFUND/LOGISTICS/REPAIR/PRICE/OTHER)",
             "entities": ["关键实体, 如产品名/部件/时间"],
             "products": ["问题明确涉及的产品/品牌名, 如 苹果13/iPhone 13/X100 Pro; 未提及给空数组"],
             "rewrites": ["2~3条改写变体, 覆盖同义词/口语/省略, 用于召回更多相关片段"],
             "sub_questions": ["若问题是复合问题则拆成子问题; 简单问题给空数组"]}
            只输出合法 JSON, 不要其他文字。例: {"intent":"WARRANTY","entities":["碎屏","X100 Pro"],"products":["X100 Pro"],"rewrites":["碎屏 免费 维修","屏幕碎裂 保修政策"],"sub_questions":[]}
            
            【上下文消歧(仅当输入含"历史对话"时执行)】
            1. 输入由"历史对话"与"当前问题"两部分组成: 历史对话按时间从早到晚排列, 当前问题是客户最新的一问。
            2. 当前问题含指代词或省略(那/它/这个/这些/多少钱/怎么修/能修吗 等)时, 必须结合历史对话展开为完整语义,
               不得孤立理解当前问题。
            3. rewrites 中至少包含一条"结合历史展开后的完整问法": 如历史提及"X100 Pro", 当前问"那换屏多少钱",
               rewrites 必须含 "X100 Pro 换屏多少钱"; 同时保留当前问题自身的独立变体。
            4. products/entities 继承: 当前问题未提及但历史对话明确涉及的(如历史提"X100 Pro", 当前问"那换屏多少钱"
               → products=["X100 Pro"]), 必须从历史继承, 不得遗漏。
            5. 无历史对话或历史与当前问题无关时, 按单轮问题正常分析, 不得强行编造上下文。
            """;

    /** 规则兜底触发阈值: 当前问题不超过该字数且带历史时, LLM 失败则合并最近用户消息为改写 */
    private static final int FALLBACK_QUERY_MAX_LEN = 15;

    /** 动态意图提示词: 意图段占位符(构建时替换为知识库意图列表) */
    private static final String INTENT_LIST_MARKER = "__INTENT_LIST__";

    /**
     * 动态意图系统提示词(知识库意图集分类): 与 SYSTEM_PROMPT 结构一致, 仅意图段不同——
     * 从知识库意图列表中选择最匹配的一项, 都不匹配则输出 OUT_OF_SCOPE。
     */
    private static final String DYNAMIC_SYSTEM_PROMPT = """
            你是企业客服知识库的"查询分析器"。给定客户问题(可能附带历史对话), 输出 JSON:
            {"intent": "意图分类(从以下知识库意图中选择最匹配的一项; 都不匹配则输出 "OUT_OF_SCOPE")",
             "entities": ["关键实体, 如产品名/部件/时间"],
             "products": ["问题明确涉及的产品/品牌名, 如 苹果13/iPhone 13/X100 Pro; 未提及给空数组"],
             "rewrites": ["2~3条改写变体, 覆盖同义词/口语/省略, 用于召回更多相关片段"],
             "sub_questions": ["若问题是复合问题则拆成子问题; 简单问题给空数组"]}
            只输出合法 JSON, 不要其他文字。例: {"intent":"保修","entities":["碎屏","X100 Pro"],"products":["X100 Pro"],"rewrites":["碎屏 免费 维修","屏幕碎裂 保修政策"],"sub_questions":[]}
            
            意图分类(从以下知识库意图中选择最匹配的一项; 都不匹配则输出 "OUT_OF_SCOPE"):
            __INTENT_LIST__
            
            【上下文消歧(仅当输入含"历史对话"时执行)】
            1. 输入由"历史对话"与"当前问题"两部分组成: 历史对话按时间从早到晚排列, 当前问题是客户最新的一问。
            2. 当前问题含指代词或省略(那/它/这个/这些/多少钱/怎么修/能修吗 等)时, 必须结合历史对话展开为完整语义,
               不得孤立理解当前问题。
            3. rewrites 中至少包含一条"结合历史展开后的完整问法": 如历史提及"X100 Pro", 当前问"那换屏多少钱",
               rewrites 必须含 "X100 Pro 换屏多少钱"; 同时保留当前问题自身的独立变体。
            4. products/entities 继承: 当前问题未提及但历史对话明确涉及的(如历史提"X100 Pro", 当前问"那换屏多少钱"
               → products=["X100 Pro"]), 必须从历史继承, 不得遗漏。
            5. 无历史对话或历史与当前问题无关时, 按单轮问题正常分析, 不得强行编造上下文。
            """;

    @Resource
    private ModelApi modelApi;
    @Resource
    private PromptSupport promptSupport;

    /**
     * 分析查询(单轮, 无上下文; 兼容旧调用方)
     *
     * @param query 原始问题
     * @return 分析结果(失败时 success=false, 字段为空)
     */
    public QueryAnalysis analyze(String query) {
        return analyze(query, null);
    }

    /**
     * 分析查询: 意图/实体/改写/子问题(支持多轮上下文消歧)
     * <p>
     * 兼容旧调用方: 无知识库意图集 → 固定 6 枚举意图, 与 Task 2 行为完全一致。
     *
     * @param query   原始问题
     * @param history 上下文轮次(可选; null/空 = 单轮行为)
     * @return 分析结果(失败时 success=false; 带历史且问题过短时附规则合并改写, 供降级召回)
     */
    public QueryAnalysis analyze(String query, List<ChatTurnDTO> history) {
        return analyze(query, history, null);
    }

    /**
     * 分析查询: 意图/实体/改写/子问题(支持多轮上下文消歧 + 知识库意图集动态分类)
     *
     * @param query   原始问题
     * @param history 上下文轮次(可选; null/空 = 单轮行为)
     * @param intents 知识库意图集(可选; null/空 = 固定 6 枚举意图, 无 OUT_OF_SCOPE, 兼容旧行为)
     * @return 分析结果(失败时 success=false; 带历史且问题过短时附规则合并改写, 供降级召回)
     */
    public QueryAnalysis analyze(String query, List<ChatTurnDTO> history, List<IntentDTO> intents) {
        QueryAnalysis result = new QueryAnalysis();
        result.setSuccess(false);
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(buildSystemPrompt(intents));
            req.setUser(buildUserPrompt(query, history));
            String resp = modelApi.chat(req).getCheckedData();
            JSONObject json = parseJson(resp);
            if (json == null) {
                // LLM 输出非 JSON: 视为分析失败, 走规则兜底
                return fallbackDisambiguate(result, query, history);
            }
            result.setIntent(clampIntent(json.getStr("intent", "OTHER"), intents));
            result.setEntities(strList(json.getJSONArray("entities")));
            result.setProducts(strList(json.getJSONArray("products")));
            result.setRewrites(strList(json.getJSONArray("rewrites")));
            result.setSubQuestions(strList(json.getJSONArray("sub_questions")));
            result.setSuccess(true);
            log.debug("[analyze][结果] intent={}, products={}, rewrites={}, subQuestions={}",
                    result.getIntent(), result.getProducts(), result.getRewrites(), result.getSubQuestions());
        } catch (Exception e) {
            log.warn("[analyze][查询分析失败, 降级用原句检索: {}]", e.getMessage());
            return fallbackDisambiguate(result, query, history);
        }
        return result;
    }

    /**
     * 系统提示词: 有知识库意图集 → 动态意图段(知识库意图列表, 不匹配钳制 OUT_OF_SCOPE);
     * 无意图集 → 固定 6 枚举提示词(兼容旧行为)。模板异常时防御性回退固定提示词。
     */
    private String buildSystemPrompt(List<IntentDTO> intents) {
        if (intents == null || intents.isEmpty()) {
            return promptSupport.get("query-analysis", SYSTEM_PROMPT);
        }
        String dynamic = promptSupport.get("query-disambiguate", DYNAMIC_SYSTEM_PROMPT);
        int marker = dynamic.indexOf(INTENT_LIST_MARKER);
        if (marker < 0) {
            log.warn("[buildSystemPrompt][动态意图模板缺占位符, 回退固定提示词]");
            return promptSupport.get("query-analysis", SYSTEM_PROMPT);
        }
        StringBuilder sb = new StringBuilder(dynamic.length() + 256);
        sb.append(dynamic, 0, marker);
        for (IntentDTO intent : intents) {
            sb.append("- ").append(intent.getName());
            if (StrUtil.isNotBlank(intent.getDescription())) {
                sb.append(": ").append(StrUtil.trim(intent.getDescription()));
            }
            sb.append('\n');
        }
        sb.append(dynamic, marker + INTENT_LIST_MARKER.length(), dynamic.length());
        return sb.toString();
    }

    /**
     * 意图钳制(结构化校验, 不信任 LLM 自由发挥): LLM 返回的意图名必须在提供的意图集中,
     * 否则钳制为 OUT_OF_SCOPE。intents 为空时返回 LLM 原值(固定枚举兼容, 缺省 OTHER)。
     */
    private String clampIntent(String rawIntent, List<IntentDTO> intents) {
        if (intents == null || intents.isEmpty()) {
            return StrUtil.isBlank(rawIntent) ? "OTHER" : rawIntent;
        }
        if (StrUtil.isBlank(rawIntent)) {
            return "OUT_OF_SCOPE";
        }
        String trimmed = StrUtil.trim(rawIntent);
        for (IntentDTO intent : intents) {
            if (intent != null && trimmed.equals(intent.getName())) {
                return intent.getName();
            }
        }
        return "OUT_OF_SCOPE";
    }

    /**
     * 组装用户消息: 历史对话(时间从早到晚) + 当前问题; 无历史时仅"当前问题"。
     * <pre>
     * 历史对话(时间从早到晚):
     * [用户] X100 Pro 碎屏能免费修吗
     * [客服] 不能, 屏幕碎裂属意外损坏, 不在免费保修范围。
     * 当前问题: 那换屏多少钱?
     * </pre>
     * role 映射: USER → 用户, 其余(AI 等) → 客服。
     */
    private String buildUserPrompt(String query, List<ChatTurnDTO> history) {
        if (history == null || history.isEmpty()) {
            return "当前问题: " + query;
        }
        StringBuilder sb = new StringBuilder("历史对话(时间从早到晚):\n");
        for (ChatTurnDTO turn : history) {
            if (turn == null || StrUtil.isBlank(turn.getContent())) {
                continue;
            }
            String role = "USER".equalsIgnoreCase(turn.getRole()) ? "用户" : "客服";
            sb.append('[').append(role).append("] ").append(StrUtil.trim(turn.getContent())).append('\n');
        }
        sb.append("当前问题: ").append(query);
        return sb.toString();
    }

    /**
     * 规则兜底消歧(纯规则, 不依赖 LLM): LLM 分析失败且 历史非空 + 当前问题很短(≤15 字, 疑似指代/省略)时,
     * 取历史最近一条 USER 消息与当前问题合并为一个 rewrite, 保证降级也有基本消歧召回。
     * 规则不解析实体, products 留空; 返回 success=false(调用方仍以原句为主, 仅补充改写)。
     * 条件不满足时返回入参 result(原失败行为: 仅 success=false, 字段为空)。
     */
    private QueryAnalysis fallbackDisambiguate(QueryAnalysis result, String query, List<ChatTurnDTO> history) {
        if (history == null || history.isEmpty() || StrUtil.isBlank(query)
                || query.trim().length() > FALLBACK_QUERY_MAX_LEN) {
            return result;
        }
        String lastUser = lastUserContent(history);
        if (StrUtil.isBlank(lastUser)) {
            return result;
        }
        String merged = mergeLastUserAndQuery(StrUtil.trim(lastUser), query.trim());
        result.setRewrites(List.of(merged));
        log.info("[analyze][规则兜底消歧] LLM 失败, 合并改写: {}", merged);
        return result;
    }

    /** 取历史中最近一条 USER 消息内容(历史按时间从早到晚, 从后往前找) */
    private String lastUserContent(List<ChatTurnDTO> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatTurnDTO turn = history.get(i);
            if (turn != null && "USER".equalsIgnoreCase(turn.getRole()) && StrUtil.isNotBlank(turn.getContent())) {
                return turn.getContent();
            }
        }
        return null;
    }

    /** 最近用户消息与当前问题去重合并: 一方包含另一方时取长句, 否则空格拼接 */
    private String mergeLastUserAndQuery(String lastUser, String query) {
        if (lastUser.contains(query)) {
            return lastUser;
        }
        if (query.contains(lastUser)) {
            return query;
        }
        return lastUser + " " + query;
    }

    /** 截取首个 { 到最后一个 } 之间的内容并解析(兼容 LLM 输出带前后缀说明) */
    private JSONObject parseJson(String resp) {
        if (StrUtil.isBlank(resp)) {
            return null;
        }
        int start = resp.indexOf('{');
        int end = resp.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSONUtil.parseObj(resp.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 数组转字符串列表(过滤空串) */
    private List<String> strList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr != null) {
            for (Object o : arr) {
                if (o != null && StrUtil.isNotBlank(o.toString())) {
                    list.add(o.toString());
                }
            }
        }
        return list;
    }

}
