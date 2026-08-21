package cn.iocoder.yudao.module.ingestion.split;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子切分: 父块 = 大段落/章节(≤ maxTokens*4), 子块 = 父块内按句切(≤ maxTokens), 子块 parentId 指向父块。
 * 检索命中子块时可用父块上下文(连贯性); 基于结构树时父块 = 章节组。
 */
@Component
@ChunkStrategy(key = "parent-child", name = "父子切分",
        description = "父块(章节/大段落)为检索单元, 子块(句子组)为精读单元, 命中子块回带父块上下文")
public class ParentChildSplitter implements ChunkSplitter {

    @Override
    public List<Chunk> split(ParsedDocument doc, SplitParams params) {
        List<Chunk> result = new ArrayList<>();
        if (doc == null || doc.isEmpty()) {
            return result;
        }
        int maxTokens = params == null ? 500 : params.getMaxTokens();
        // 复用结构切分的章节分组: 整组作为父块, 超长切子块
        String text = doc.toPlainText();
        String[] paragraphs = text.split("\\n\\s*\\n");
        long parentSeq = 0;
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            parentSeq++;
            Chunk parent = new Chunk(trimmed, "SEMANTIC");
            parent.setMetadata("{\"parent\":true}");
            result.add(parent);
            if (SplitUtils.estimateTokens(trimmed) > maxTokens) {
                List<String> subChunks = SplitUtils.splitBySentences(trimmed, maxTokens);
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
}
