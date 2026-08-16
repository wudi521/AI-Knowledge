package cn.iocoder.yudao.module.ingestion.split;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FAQ 切分: 识别 "问/答" 或 "Q/A" 对
 */
@Component
public class FaqSplitter implements ChunkSplitter {

    private static final Pattern FAQ_PATTERN = Pattern.compile(
            "(?:问|Q)\\s*[:：]\\s*(.+?)(?:答|A)\\s*[:：]\\s*(.+?)(?=(?:问|Q)\\s*[:：]|$)",
            Pattern.DOTALL);

    @Override
    public List<Chunk> split(String text, int maxTokens) {
        List<Chunk> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher matcher = FAQ_PATTERN.matcher(text);
        while (matcher.find()) {
            String question = matcher.group(1).trim();
            String answer = matcher.group(2).trim();
            result.add(new Chunk("问: " + question + "\n答: " + answer, "FAQ"));
        }
        // 无 FAQ 结构时, 退化为语义切分
        if (result.isEmpty()) {
            SemanticSplitter fallback = new SemanticSplitter();
            result.addAll(fallback.split(text, maxTokens));
        }
        return result;
    }

}
