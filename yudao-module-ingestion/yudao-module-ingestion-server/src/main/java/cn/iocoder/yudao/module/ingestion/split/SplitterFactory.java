package cn.iocoder.yudao.module.ingestion.split;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 切分策略选择器(按 ai_knowledge_base.chunk_strategy)
 */
@Component
public class SplitterFactory {

    private final Map<String, ChunkSplitter> splitters = new HashMap<>();

    public SplitterFactory(SemanticSplitter semanticSplitter,
                           ParentChildSplitter parentChildSplitter,
                           TableSplitter tableSplitter,
                           FaqSplitter faqSplitter,
                           PolicySplitter policySplitter) {
        splitters.put("Semantic", semanticSplitter);
        splitters.put("ParentChild", parentChildSplitter);
        splitters.put("Table", tableSplitter);
        splitters.put("FAQ", faqSplitter);
        splitters.put("Policy", policySplitter);
    }

    /**
     * 按策略名获取切分器, 未知策略回退 Semantic
     */
    public ChunkSplitter getSplitter(String strategy) {
        if (StrUtil.isBlank(strategy)) {
            return splitters.get("Semantic");
        }
        return splitters.getOrDefault(strategy, splitters.get("Semantic"));
    }

}
