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
 * 专利领域 Rerank 插件：复用通用相关性打分，只保留真正属于 Rerank 阶段的内容约束。
 *
 * <p>申请号/公布号若已由 Scope 插件权威绑定 documentIds，Rerank 不得再次要求每个 chunk 文本重复出现编号；
 * 否则会把同一目标文档中的正文片段误删。没有 Scope provenance 的直接调用仍保留原 fail-closed 文本门禁。</p>
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
        boolean hasDocumentIdentifier = hints.hasExactDocumentIdentifier();
        boolean hasClaimConstraint = hints.getClaimNos() != null && !hints.getClaimNos().isEmpty();
        if (!hasDocumentIdentifier && !hasClaimConstraint) {
            return new RetrievalRerankResult(pluginId(), base, false, null, 0L);
        }

        // 文档标识已经在 Scope 阶段绑定时，不再用 chunk 文本重复验证文档身份。
        boolean requireDocumentIdentifierInChunk = hasDocumentIdentifier && !context.hardScoped();
        List<Map.Entry<Integer, Float>> filtered = new ArrayList<>();
        for (Map.Entry<Integer, Float> ranked : base) {
            int index = ranked.getKey();
            if (index < 0 || index >= context.candidateContents().size()) continue;
            String content = StrUtil.nullToEmpty(context.candidateContents().get(index));
            if (requireDocumentIdentifierInChunk
                    && StrUtil.isNotBlank(hints.getApplicationNo())
                    && !content.contains(hints.getApplicationNo())) continue;
            if (requireDocumentIdentifierInChunk
                    && StrUtil.isNotBlank(hints.getPublicationNo())
                    && !normalizePublication(content).contains(normalizePublication(hints.getPublicationNo()))) continue;
            if (hasClaimConstraint && hints.getClaimNos().stream().noneMatch(no -> matchesClaim(content, no))) continue;

            float score = ranked.getValue() == null ? 0F : ranked.getValue();
            if (hasDocumentIdentifier) score += 2F;
            if (hasClaimConstraint) score += 2F;
            filtered.add(Map.entry(index, score));
        }
        filtered.sort(Map.Entry.<Integer, Float>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        return new RetrievalRerankResult(pluginId(), filtered, false,
                filtered.isEmpty() ? "patent rerank constraints removed all candidates" : null, 0L);
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
