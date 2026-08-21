package cn.iocoder.yudao.module.ingestion.split;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FAQ 切分: 识别 "问/答" 或 "Q/A" 对; 无 FAQ 结构时退化为语义切分
 */
@Component
@ChunkStrategy(key = "faq", name = "问答切分",
        description = "识别 问/答 或 Q/A 对(每对一块); 适合 FAQ 手册类文档, 无问答结构自动退化为语义切分")
public class FaqSplitter implements ChunkSplitter {

    private static final Pattern FAQ_PATTERN = Pattern.compile(
            "(?:问|Q)\\s*[:：]\\s*(.+?)(?:答|A)\\s*[:：]\\s*(.+?)(?=(?:问|Q)\\s*[:：]|$)",
            Pattern.DOTALL);

    @Override
    public List<Chunk> split(ParsedDocument doc, SplitParams params) {
        List<Chunk> result = new ArrayList<>();
        if (doc == null || doc.isEmpty()) {
            return result;
        }
        int maxTokens = params == null ? 500 : params.getMaxTokens();
        String text = doc.toPlainText();
        Matcher matcher = FAQ_PATTERN.matcher(text);
        while (matcher.find()) {
            String question = matcher.group(1).trim();
            String answer = matcher.group(2).trim();
            result.add(new Chunk("问: " + question + "\n答: " + answer, "FAQ"));
        }
        // 无 FAQ 结构时, 退化为语义切分
        if (result.isEmpty()) {
            result.addAll(new SemanticSplitter().split(doc, params));
        }
        return result;
    }
}
