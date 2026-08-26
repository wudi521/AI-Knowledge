package cn.iocoder.yudao.module.retrieval.service.search.rerank;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.retrieval.service.domain.PatentQueryPreParser;
import cn.iocoder.yudao.module.retrieval.service.search.Reranker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 专利领域 Rerank 插件：复用通用相关性打分，并把专利强标识硬过滤留在领域边界内。
 */
@Component
public class PatentRerankPlugin implements RetrievalRerankPlugin {

    private final Reranker reranker;
    private final PatentQueryPreParser preParser;

    public PatentRerankPlugin(Reranker reranker, PatentQueryPreParser preParser) {
        this.reranker = reranker;
        this.preParser = preParser;
    }

    @Override
    public String pluginId() {
        return "retrieval-rerank:patent";
    }

    @Override
    public Set<String> supportedDomains() {
        return Set.of("PATENT");
    }

    @Override
    public RetrievalRerankResult rerank(RetrievalRerankContext context) {
        List<Map.Entry<Integer, Float>> base = reranker.rerank(context.query(), context.candidateContents());
        PatentQueryPreParser.PatentQueryHints hints = preParser.parse(context.query());
        if (hints == null) {
            return new RetrievalRerankResult(pluginId(), base, false, null, 0L);
        }
        boolean exact = hints.hasExactDocumentIdentifier()
                || (hints.getClaimNos() != null && !hints.getClaimNos().isEmpty());
        if (!exact) {
            return new RetrievalRerankResult(pluginId(), base, false, null, 0L);
        }

        List<Map.Entry<Integer, Float>> filtered = new ArrayList<>();
        for (Map.Entry<Integer, Float> ranked : base) {
            int index = ranked.getKey();
            if (index < 0 || index >= context.candidateContents().size()) continue;
            String content = StrUtil.nullToEmpty(context.candidateContents().get(index));
            if (StrUtil.isNotBlank(hints.getApplicationNo()) && !content.contains(hints.getApplicationNo())) continue;
            if (StrUtil.isNotBlank(hints.getPublicationNo())
                    && !normalizePublication(content).contains(normalizePublication(hints.getPublicationNo()))) continue;
            if (hints.getClaimNos() != null && !hints.getClaimNos().isEmpty()
                    && hints.getClaimNos().stream().noneMatch(no -> matchesClaim(content, no))) continue;

            float score = ranked.getValue() == null ? 0F : ranked.getValue();
            if (hints.hasExactDocumentIdentifier()) score += 2F;
            if (hints.getClaimNos() != null && !hints.getClaimNos().isEmpty()) score += 2F;
            filtered.add(Map.entry(index, score));
        }
        filtered.sort(Map.Entry.<Integer, Float>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        return new RetrievalRerankResult(pluginId(), filtered, false,
                filtered.isEmpty() ? "patent exact identifier gate removed all candidates" : null, 0L);
    }

    private boolean matchesClaim(String content, Integer claimNo) {
        if (claimNo == null) return false;
        return Pattern.compile("(?s).*\\[权利要求]\\s*" + Pattern.quote(String.valueOf(claimNo)) + "(?:\\D.*|$)")
                .matcher(content).matches();
    }

    private String normalizePublication(String value) {
        return StrUtil.nullToEmpty(value).replaceAll("\\s+", "").toUpperCase();
    }
}
