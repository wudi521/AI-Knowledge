package cn.iocoder.yudao.module.ingestion.split;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条款切分: 按 "第X条" / "X.X" / "一、二、三、" 编号切分, 编号保留在块内容中(条款自包含)
 */
@Component
@ChunkStrategy(key = "policy", name = "条款切分",
        description = "按条款编号(第X条/X.X/一、二、)切分, 条款编号保留在块内; 适合政策/法规/合同类文档")
public class PolicySplitter implements ChunkSplitter {

    private static final Pattern POLICY_PATTERN = Pattern.compile(
            "((?:第[一二三四五六七八九十百0-9]+条|[0-9]+\\.[0-9]+|[一二三四五六七八九十]+、))");

    @Override
    public List<Chunk> split(ParsedDocument doc, SplitParams params) {
        List<Chunk> result = new ArrayList<>();
        if (doc == null || doc.isEmpty()) {
            return result;
        }
        String text = doc.toPlainText();
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
