package cn.iocoder.yudao.module.ingestion.split;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义切分: 按空行分段, 超长段按句号/逗号再切, 控制单块 ≤ maxTokens
 */
@Component
public class SemanticSplitter implements ChunkSplitter {

    @Override
    public List<Chunk> split(String text, int maxTokens) {
        List<Chunk> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        // 1. 按空行切大段
        String[] paragraphs = text.split("\\n\\s*\\n");
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 2. 超长段按句子切
            if (estimateTokens(trimmed) > maxTokens) {
                for (String sentence : splitLongParagraph(trimmed, maxTokens)) {
                    result.add(new Chunk(sentence, "SEMANTIC"));
                }
            } else {
                result.add(new Chunk(trimmed, "SEMANTIC"));
            }
        }
        return result;
    }

    /** 粗略估计 token 数: 中文按 1.5 字符/token, 英文按 4 字符/token */
    private int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 1.5);
    }

    /** 长段按句号切, 合并到 ≤ maxTokens */
    private List<String> splitLongParagraph(String para, int maxTokens) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : para.split("(?<=[。！？.!?])")) {
            if (estimateTokens(current.toString()) + estimateTokens(sentence) > maxTokens
                    && current.length() > 0) {
                sentences.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            sentences.add(current.toString().trim());
        }
        return sentences;
    }

}
