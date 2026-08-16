package cn.iocoder.yudao.module.ingestion.split;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子切分: 父块 = 大段落(≤ maxTokens*4); 子块 = 父块内按句切(≤ maxTokens), 子块 parentId 指向父块
 */
@Component
public class ParentChildSplitter implements ChunkSplitter {

    private final SemanticSplitter semanticSplitter = new SemanticSplitter();

    @Override
    public List<Chunk> split(String text, int maxTokens) {
        List<Chunk> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        // 1. 先按段落粗切(复用语义切分的段落边界逻辑)
        String[] paragraphs = text.split("\\n\\s*\\n");
        long parentSeq = 0;
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 父块
            parentSeq++;
            Chunk parent = new Chunk(trimmed, "SEMANTIC");
            parent.setMetadata("{\"parent\":true}");
            result.add(parent);
            // 2. 子块: 父块超长时切子块, 引用父块 index
            if (estimateTokens(trimmed) > maxTokens) {
                List<String> subChunks = splitBySentences(trimmed, maxTokens);
                for (String sub : subChunks) {
                    Chunk child = new Chunk(sub, "SEMANTIC");
                    child.setParentId(parentSeq);
                    child.setMetadata("{\"parent\":false}");
                    result.add(child);
                }
            }
        }
        return result;
    }

    private int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 1.5);
    }

    private List<String> splitBySentences(String para, int maxTokens) {
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
