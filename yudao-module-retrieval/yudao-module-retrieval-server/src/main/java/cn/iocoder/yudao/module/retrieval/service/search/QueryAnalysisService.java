package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.ChatTurnDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询语义理解/改写/拆解(LLM; 失败降级仅返回原句)
 */
@Slf4j
@Service
public class QueryAnalysisService {

    private static final String SYSTEM_PROMPT = """
            你是企业客服知识库的"查询分析器"。给定客户问题, 输出 JSON:
            {"intent": "意图分类(WARRANTY/REFUND/LOGISTICS/REPAIR/PRICE/OTHER)",
             "entities": ["关键实体, 如产品名/部件/时间"],
             "products": ["问题明确涉及的产品/品牌名, 如 苹果13/iPhone 13/X100 Pro; 未提及给空数组"],
             "rewrites": ["2~3条改写变体, 覆盖同义词/口语/省略, 用于召回更多相关片段"],
             "sub_questions": ["若问题是复合问题则拆成子问题; 简单问题给空数组"]}
            只输出合法 JSON, 不要其他文字。例: {"intent":"WARRANTY","entities":["碎屏","X100 Pro"],"products":["X100 Pro"],"rewrites":["碎屏 免费 维修","屏幕碎裂 保修政策"],"sub_questions":[]}
            """;

    @Resource
    private ModelApi modelApi;

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
     * 分析查询: 意图/实体/改写/子问题(支持多轮上下文)
     *
     * @param query   原始问题
     * @param history 上下文轮次(可选; T1 仅接收存储, 提示词保持单轮不变, Task 2 融入)
     * @return 分析结果(失败时 success=false, 字段为空)
     */
    public QueryAnalysis analyze(String query, List<ChatTurnDTO> history) {
        QueryAnalysis result = new QueryAnalysis();
        result.setSuccess(false);
        try {
            // TODO(Task 2): 将 history 融入 SYSTEM_PROMPT/用户消息(多轮消歧改写), T1 保持单轮提示词不变
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(SYSTEM_PROMPT);
            req.setUser(query);
            String resp = modelApi.chat(req).getCheckedData();
            JSONObject json = parseJson(resp);
            if (json == null) {
                return result;
            }
            result.setIntent(json.getStr("intent", "OTHER"));
            result.setEntities(strList(json.getJSONArray("entities")));
            result.setProducts(strList(json.getJSONArray("products")));
            result.setRewrites(strList(json.getJSONArray("rewrites")));
            result.setSubQuestions(strList(json.getJSONArray("sub_questions")));
            result.setSuccess(true);
        } catch (Exception e) {
            log.warn("[analyze][查询分析失败, 降级用原句检索: {}]", e.getMessage());
        }
        return result;
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
