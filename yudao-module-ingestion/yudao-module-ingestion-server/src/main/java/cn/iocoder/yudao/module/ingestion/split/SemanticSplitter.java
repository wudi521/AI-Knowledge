package cn.iocoder.yudao.module.ingestion.split;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义切分: 按段落/句子切分(无结构文本兜底)。
 * 基于结构化文档时: 段落元素为切分单元, 超长段按句再切; 支持 overlap 与标题链注入。
 */
@Component
@ChunkStrategy(key = "semantic", name = "语义切分",
        description = "按段落/句子切分, 保持语义完整; 无标题结构的文本(扫描转换/纯文本)兜底策略")
public class SemanticSplitter implements ChunkSplitter {

    @Override
    public List<Chunk> split(ParsedDocument doc, SplitParams params) {
        List<Chunk> result = new ArrayList<>();
        if (doc == null || doc.isEmpty()) {
            return result;
        }
        int maxTokens = params == null ? 500 : params.getMaxTokens();
        int overlap = params == null ? 0 : params.getOverlap();
        boolean titleChain = params == null || params.isTitleChain();
        List<String> blocks = new ArrayList<>();
        for (ParsedDocument.Element e : doc.getElements()) {
            String text = e.text();
            if (StrUtil.isBlank(text)) {
                continue;
            }
            if (SplitUtils.estimateTokens(text) > maxTokens) {
                // 超长元素按句切分
                blocks.addAll(SplitUtils.splitBySentences(text, maxTokens));
            } else {
                blocks.add(text);
            }
        }
        if (overlap > 0) {
            blocks = SplitUtils.applyOverlap(blocks, overlap);
        }
        for (String block : blocks) {
            result.add(new Chunk(block, "SEMANTIC"));
        }
        return result;
    }
}
