package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.model.api.dto.ModelRerankReqDTO;
import cn.iocoder.yudao.module.retrieval.service.prompt.PromptSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 重排(混合策略: BGE 重排优先 → LLM 打分兜底 → 保持原序; 各级失败均记日志降级)
 * <p>
 * 1. BGE 重排: modelApi.rerank(LM Studio /v1/rerank), 返回与候选一一对应的相关性分, 降序排序;
 *    异常/结果数量不匹配 → 转 LLM 打分
 * 2. LLM 打分: qwen 一次 chat 调用, 输出与候选顺序一一对应的 0~1 分数 JSON 数组, 降序排序;
 *    解析失败/数量不匹配 → 保持原序
 * 3. 保持原序兜底(分数 0)
 */
@Slf4j
@Service
public class Reranker {

    private static final String LLM_SCORE_SYSTEM_PROMPT = """
            你是检索结果的"相关性重排器"。给定一个用户问题与若干候选片段(带编号 [0],[1]...), 判断每个片段与问题的相关性, 输出 JSON 数组, 元素与候选顺序一一对应, 取值 0~1(1=高度相关)。
            只输出 JSON 数组, 不要其他文字。例: [0.95, 0.3, 0.1]
            """;

    /** 单条候选截断长度(字): 控制 BGE 输入 token, 防止 llama.cpp physical-batch-size 超限(默认 512) */
    private static final int CANDIDATE_MAX_LEN = 256;

    @Resource
    private ModelApi modelApi;
    @Resource
    private PromptSupport promptSupport;

    /**
     * 重排候选
     *
     * @param query 原始问题
     * @param contents 候选内容(与候选顺序一致)
     * @return 按重排分降序的 [原文索引, 分数] 列表(覆盖全部候选)
     */
    public List<Map.Entry<Integer, Float>> rerank(String query, List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        // 0. 截断候选内容: 长文档会被 llama.cpp 拒绝(570 tokens > batch 512), 截断到 256 字保语义且不超限
        List<String> truncated = new ArrayList<>(contents.size());
        for (String content : contents) {
            truncated.add(StrUtil.sub(StrUtil.nullToEmpty(content), 0, CANDIDATE_MAX_LEN));
        }
        // 1. BGE 重排优先
        try {
            ModelRerankReqDTO req = new ModelRerankReqDTO();
            req.setQuery(query);
            req.setDocuments(truncated);
            List<Float> scores = modelApi.rerank(req).getCheckedData();
            if (scores != null && scores.size() == contents.size()) {
                return sortByScoreDesc(scores);
            }
            log.warn("[rerank][BGE 重排结果数量不匹配({}/{}), 转LLM打分]", scores == null ? 0 : scores.size(), contents.size());
        } catch (Exception e) {
            log.warn("[rerank][BGE重排失败, 转LLM打分: {}]", e.getMessage());
        }
        // 2. LLM 打分兜底
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < contents.size(); i++) {
                sb.append("[").append(i).append("] ").append(contents.get(i)).append("\n\n");
            }
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get("rerank-llm", LLM_SCORE_SYSTEM_PROMPT));
            req.setUser("问题: " + query + "\n\n候选片段:\n" + sb);
            String resp = modelApi.chat(req).getCheckedData();
            List<Float> scores = parseScoreArray(resp, contents.size());
            if (scores != null) {
                return sortByScoreDesc(scores);
            }
            log.warn("[rerank][LLM 打分结果无法解析, 降级保持原序]");
        } catch (Exception e) {
            log.warn("[rerank][LLM 打分失败, 降级保持原序: {}]", e.getMessage());
        }
        // 3. 保持原序(分数 0)
        return keepOrder(contents);
    }

    /** 按分数降序返回 [索引, 分数] 列表 */
    private List<Map.Entry<Integer, Float>> sortByScoreDesc(List<Float> scores) {
        List<Map.Entry<Integer, Float>> list = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            Float score = scores.get(i);
            list.add(Map.entry(i, score == null ? 0F : score));
        }
        list.sort(Map.Entry.<Integer, Float>comparingByValue().reversed());
        return list;
    }

    /** 保持原序(分数 0) */
    private List<Map.Entry<Integer, Float>> keepOrder(List<String> contents) {
        List<Map.Entry<Integer, Float>> list = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            list.add(Map.entry(i, 0F));
        }
        return list;
    }

    /** 解析 LLM 输出的 JSON 分数数组(截取首个 [ 到最后一个 ]; 数量不匹配返回 null) */
    private List<Float> parseScoreArray(String resp, int size) {
        if (resp == null) {
            return null;
        }
        int start = resp.indexOf('[');
        int end = resp.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JSONArray arr = JSONUtil.parseArray(resp.substring(start, end + 1));
            if (arr.size() != size) {
                return null;
            }
            List<Float> scores = new ArrayList<>();
            for (Object o : arr) {
                scores.add(((Number) o).floatValue());
            }
            return scores;
        } catch (Exception e) {
            return null;
        }
    }

}
