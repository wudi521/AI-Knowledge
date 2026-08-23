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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 重排: BGE 优先 → LLM 兜底 → 原序。
 * 对专利强标识(申请号/公布号/权利要求号)增加确定性 boost，防止语义相关但属于其他专利的片段排到前面。
 */
@Slf4j
@Service
public class Reranker {

    private static final String LLM_SCORE_SYSTEM_PROMPT = """
            你是检索结果的"相关性重排器"。给定一个用户问题与若干候选片段(带编号 [0],[1]...), 判断每个片段与问题的相关性, 输出 JSON 数组, 元素与候选顺序一一对应, 取值 0~1。
            只输出 JSON 数组, 不要其他文字。
            """;

    private static final int CANDIDATE_MAX_LEN = 256;
    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)(20\\d{10}\\.\\d)(?!\\d)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");
    private static final Pattern CLAIM_NO = Pattern.compile("权利要求\\s*(\\d+)");

    @Resource
    private ModelApi modelApi;
    @Resource
    private PromptSupport promptSupport;

    public List<Map.Entry<Integer, Float>> rerank(String query, List<String> contents) {
        if (contents == null || contents.isEmpty()) return List.of();
        List<String> truncated = new ArrayList<>(contents.size());
        for (String content : contents) {
            truncated.add(StrUtil.sub(StrUtil.nullToEmpty(content), 0, CANDIDATE_MAX_LEN));
        }

        List<Float> baseScores = null;
        try {
            ModelRerankReqDTO req = new ModelRerankReqDTO();
            req.setQuery(query);
            req.setDocuments(truncated);
            List<Float> scores = modelApi.rerank(req).getCheckedData();
            if (scores != null && scores.size() == contents.size()) {
                baseScores = scores;
            } else {
                log.warn("[rerank][BGE 重排结果数量不匹配({}/{}), 转LLM打分]", scores == null ? 0 : scores.size(), contents.size());
            }
        } catch (Exception e) {
            log.warn("[rerank][BGE重排失败, 转LLM打分: {}]", e.getMessage());
        }

        if (baseScores == null) {
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < contents.size(); i++) sb.append("[").append(i).append("] ").append(contents.get(i)).append("\n\n");
                ModelChatReqDTO req = new ModelChatReqDTO();
                req.setSystem(promptSupport.get("rerank-llm", LLM_SCORE_SYSTEM_PROMPT));
                req.setUser("问题: " + query + "\n\n候选片段:\n" + sb);
                baseScores = parseScoreArray(modelApi.chat(req).getCheckedData(), contents.size());
            } catch (Exception e) {
                log.warn("[rerank][LLM 打分失败, 降级保持原序: {}]", e.getMessage());
            }
        }

        if (baseScores == null) {
            baseScores = new ArrayList<>();
            for (int i = 0; i < contents.size(); i++) baseScores.add(0F);
        }
        return sortWithStructuredBoost(query, contents, baseScores);
    }

    /**
     * 专利结构化 boost。搜索头中已经写入 [申请号]/[公布号]/[权利要求]，因此这里无需额外 RPC。
     * boost 大于普通 0~1 rerank 分，确保明确编号查询不会被其他专利的语义相似片段压过。
     */
    private List<Map.Entry<Integer, Float>> sortWithStructuredBoost(String query, List<String> contents, List<Float> scores) {
        String applicationNo = first(APPLICATION_NO, query);
        String publicationNo = first(PUBLICATION_NO, query);
        String claimNo = first(CLAIM_NO, query);
        List<Map.Entry<Integer, Float>> result = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            float score = scores.get(i) == null ? 0F : scores.get(i);
            String content = StrUtil.nullToEmpty(contents.get(i));
            boolean documentMatched = false;
            if (StrUtil.isNotBlank(applicationNo)) {
                documentMatched = content.contains(applicationNo);
                score += documentMatched ? 2.0F : -2.0F;
            } else if (StrUtil.isNotBlank(publicationNo)) {
                String normalizedContent = content.replaceAll("\\s+", " ").toUpperCase();
                documentMatched = normalizedContent.contains(publicationNo.replaceAll("\\s+", " ").toUpperCase());
                score += documentMatched ? 2.0F : -2.0F;
            }
            if (StrUtil.isNotBlank(claimNo)) {
                boolean claimMatched = content.matches("(?s).*\\[权利要求]\\s*" + Pattern.quote(claimNo) + "(?:\\D.*|$)");
                score += claimMatched ? 2.0F : -1.0F;
                if ((StrUtil.isNotBlank(applicationNo) || StrUtil.isNotBlank(publicationNo)) && !documentMatched) {
                    score -= 3.0F;
                }
            }
            result.add(Map.entry(i, score));
        }
        result.sort(Map.Entry.<Integer, Float>comparingByValue().reversed());
        return result;
    }

    private String first(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(matcher.groupCount() >= 1 ? 1 : 0).trim() : null;
    }

    private List<Float> parseScoreArray(String resp, int size) {
        if (resp == null) return null;
        int start = resp.indexOf('[');
        int end = resp.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        try {
            JSONArray arr = JSONUtil.parseArray(resp.substring(start, end + 1));
            if (arr.size() != size) return null;
            List<Float> scores = new ArrayList<>();
            for (Object o : arr) scores.add(((Number) o).floatValue());
            return scores;
        } catch (Exception e) {
            return null;
        }
    }
}
