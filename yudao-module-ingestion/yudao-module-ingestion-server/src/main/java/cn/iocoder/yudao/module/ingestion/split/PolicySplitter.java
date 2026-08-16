package cn.iocoder.yudao.module.ingestion.split;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条款切分: 按 "第X条" 或 "X.X" 或 "一、二、三、" 编号切分
 */
@Component
public class PolicySplitter implements ChunkSplitter {

    private static final Pattern POLICY_PATTERN = Pattern.compile(
            "((?:第[一二三四五六七八九十百0-9]+条|[0-9]+\\.[0-9]+|[一二三四五六七八九十]+、))");

    @Override
    public List<Chunk> split(String text, int maxTokens) {
        List<Chunk> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher matcher = POLICY_PATTERN.matcher(text);
        int lastEnd = 0;
        String lastTitle = "";
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String body = text.substring(lastEnd, matcher.start()).trim();
                if (!body.isEmpty()) {
                    result.add(new Chunk((lastTitle + " " + body).trim(), "POLICY"));
                }
            }
            lastTitle = matcher.group(1).trim();
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            String body = text.substring(lastEnd).trim();
            if (!body.isEmpty()) {
                result.add(new Chunk((lastTitle + " " + body).trim(), "POLICY"));
            }
        }
        return result;
    }

}
