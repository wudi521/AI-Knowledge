package cn.iocoder.yudao.module.ingestion.split;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格切分: 识别行(按换行), 每行一个 chunk, 表头(第一行)拼入每行内容
 */
@Component
public class TableSplitter implements ChunkSplitter {

    @Override
    public List<Chunk> split(String text, int maxTokens) {
        List<Chunk> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String[] lines = text.split("\\r?\\n");
        if (lines.length == 0) {
            return result;
        }
        String header = lines[0].trim(); // 表头
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String content = header.isEmpty() ? line : "表头: " + header + " | 行: " + line;
            result.add(new Chunk(content, "TABLE"));
        }
        return result;
    }

}
