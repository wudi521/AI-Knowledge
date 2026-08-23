package cn.iocoder.yudao.module.evidence.service.rule;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * P0-10: 专利计数确定性短路(计数/枚举类问题不走 top-K RAG, 否则只召回部分专利导致漏数)。
 * <p>
 * 命中"当前知识库有几个专利"等计数意图时, 直接调用知识模块统计去重专利数, 0 LLM / 0 向量检索。
 * 未命中 / RPC 失败 → 返回 null 走原管线。
 */
@Slf4j
@Component
public class PatentCountShortcut {

    private static final Pattern COUNT_PATTERN = Pattern.compile(
            "几个专利|多少专利|专利.{0,4}数量|共有.{0,6}专利|收录.{0,6}专利|多少项专利|专利.{0,4}总数");

    @Resource
    private KnowledgeApi knowledgeApi;

    /** 命中计数意图 → 返回确定性回答; 否则 null */
    public String evaluate(String query, List<Long> kbIds) {
        if (StrUtil.isBlank(query) || !COUNT_PATTERN.matcher(query).find()
                || kbIds == null || kbIds.size() != 1 || kbIds.get(0) == null) {
            return null;
        }
        Long kbId = kbIds.get(0);
        try {
            Integer count = knowledgeApi.countDistinctPatents(kbId).getCheckedData();
            if (count == null) {
                return null;
            }
            return "当前知识库共收录 " + count + " 项专利。";
        } catch (Exception e) {
            log.warn("[evaluate][专利计数短路失败, 走原管线: {}]", e.getMessage());
            return null;
        }
    }

}
