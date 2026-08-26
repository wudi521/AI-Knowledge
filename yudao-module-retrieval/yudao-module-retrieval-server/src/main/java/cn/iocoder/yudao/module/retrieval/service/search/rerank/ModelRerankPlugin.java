package cn.iocoder.yudao.module.retrieval.service.search.rerank;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.retrieval.service.search.Reranker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 默认通用模型 Rerank 插件。 */
@Component
public class ModelRerankPlugin implements RetrievalRerankPlugin {

    private final Reranker reranker;

    public ModelRerankPlugin(Reranker reranker) {
        this.reranker = reranker;
    }

    @Override
    public String pluginId() {
        return "retrieval-rerank:model";
    }

    @Override
    public Set<String> supportedDomains() {
        return Set.of("*");
    }

    @Override
    public RetrievalRerankResult rerank(RetrievalRerankContext context) {
        List<String> contents = context.candidateContents();
        if (contents.isEmpty() || contents.stream().allMatch(StrUtil::isBlank)) {
            List<Map.Entry<Integer, Float>> identity = new ArrayList<>();
            for (int i = 0; i < contents.size(); i++) identity.add(Map.entry(i, 0F));
            return new RetrievalRerankResult(pluginId(), identity, false, null, 0L);
        }
        return new RetrievalRerankResult(pluginId(), reranker.rerank(context.query(), contents), false, null, 0L);
    }
}
