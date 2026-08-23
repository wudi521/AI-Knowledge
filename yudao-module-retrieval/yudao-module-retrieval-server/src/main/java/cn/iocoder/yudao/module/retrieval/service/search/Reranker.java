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
 *
 * 专利强标识查询采用 fail-closed 硬过滤：用户明确给出申请号/公布号/权利要求号时，
 * 不允许其它专利或其它 claim 仅凭语义相似度进入最终结果。
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

    @Resource private ModelApi modelApi;
    @Resource private PromptSupport promptSupport;

    public List<Map.Entry<Integer, Float>> rerank(String query, List<String> contents) {
        if (contents == null || contents.isEmpty()) return List.of();
        List<String> truncated = new ArrayList<>(contents.size());
        for (String content : contents) truncated.add(StrUtil.sub(StrUtil.nullToEmpty(content), 0, CANDIDATE_MAX_LEN));

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
        return sortWithPatentHardFilter(query, contents, baseScores);
    }

    private List<Map.Entry<Integer, Float>> sortWithPatentHardFilter(String query, List<String> contents, List<Float> scores) {
        String applicationNo = first(APPLICATION_NO, query);
        String publicationNo = first(PUBLICATION_NO, query);
        String claimNo = first(CLAIM_NO, query);
        boolean exactPatentQuery = StrUtil.isNotBlank(applicationNo) || StrUtil.isNotBlank(publicationNo) || StrUtil.isNotBlank(claimNo);

        List<Map.Entry<Integer, Float>> result = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            String content = StrUtil.nullToEmpty(contents.get(i));
            if (StrUtil.isNotBlank(applicationNo) && !content.contains(applicationNo)) continue;
            if (StrUtil.isNotBlank(publicationNo)) {
                String normalizedContent = normalizePublication(content);
                if (!normalizedContent.contains(normalizePublication(publicationNo))) continue;
            }
            if (StrUtil.isNotBlank(claimNo) && !matchesClaim(content, claimNo)) continue;

            float score = scores.get(i) == null ? 0F : scores.get(i);
            if (StrUtil.isNotBlank(applicationNo) || StrUtil.isNotBlank(publicationNo)) score += 2F;
            if (StrUtil.isNotBlank(claimNo)) score += 2F;
            result.add(Map.entry(i, score));
        }

        if (exactPatentQuery && result.isEmpty()) {
            log.warn("[rerank][专利精确标识硬过滤后无候选: applicationNo={}, publicationNo={}, claimNo={}]",
                    applicationNo, publicationNo, claimNo);
            return List.of();
        }
        result.sort(Map.Entry.<Integer, Float>comparingByValue().reversed());
        return result;
    }

    private boolean matchesClaim(String content, String claimNo) {
        return Pattern.compile("(?s).*\\[权利要求]\\s*" + Pattern.quote(claimNo) + "(?:\\D.*|$)").matcher(content).matches();
    }

    private String normalizePublication(String value) {
        return StrUtil.nullToEmpty(value).replaceAll("\\s+", "").toUpperCase();
    }

    private String first(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(matcher.groupCount() >= 1 ? 1 : 0).trim() : null;
    }

    private List<Float> parseScoreArray(String resp, int size) {
        if (resp == null) return null;
        int start = resp.indexOf('['), end = resp.lastIndexOf(']');
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
