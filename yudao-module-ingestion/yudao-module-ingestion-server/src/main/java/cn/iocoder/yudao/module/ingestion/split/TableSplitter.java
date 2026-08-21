package cn.iocoder.yudao.module.ingestion.split;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格切分: 基于结构树的 TableElement, 表头拼入每行(每行一块); 行数多时按行区间分块。
 * 无表格结构时退化为按行切分(兼容旧行为)。
 */
@Component
@ChunkStrategy(key = "table", name = "表格切分",
        description = "表格每行一个片段, 表头注入每行(保持行语义完整); 适合 Excel/PDF 表格为主文档")
public class TableSplitter implements ChunkSplitter {

    @Override
    public List<Chunk> split(ParsedDocument doc, SplitParams params) {
        List<Chunk> result = new ArrayList<>();
        if (doc == null || doc.isEmpty()) {
            return result;
        }
        int maxTokens = params == null ? 500 : params.getMaxTokens();
        boolean hasTable = false;
        for (ParsedDocument.Element e : doc.getElements()) {
            if (e instanceof ParsedDocument.TableElement table) {
                hasTable = true;
                splitTable(result, table, maxTokens);
            } else if (!(e instanceof ParsedDocument.HeadingElement)) {
                // 表格外的非标题文本也按行保留(避免丢内容)
                splitPlainLines(result, e.text());
            }
        }
        if (!hasTable) {
            // 无表格元素: 按行切分(兼容旧行为)
            splitPlainLines(result, doc.toPlainText());
        }
        return result;
    }

    private void splitTable(List<Chunk> result, ParsedDocument.TableElement table, int maxTokens) {
        String header = table.getHeader().isEmpty() ? "" : String.join(" | ", table.getHeader());
        StringBuilder current = new StringBuilder();
        int rowCount = 0;
        for (List<String> row : table.getRows()) {
            String line = String.join(" | ", row);
            String content = header.isEmpty() ? line : "表头: " + header + " | 行: " + line;
            if (SplitUtils.estimateTokens(current.toString()) + SplitUtils.estimateTokens(content) > maxTokens
                    && rowCount > 0) {
                result.add(new Chunk(current.toString().trim(), "TABLE"));
                current.setLength(0);
                rowCount = 0;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(content);
            rowCount++;
        }
        if (current.length() > 0) {
            result.add(new Chunk(current.toString().trim(), "TABLE"));
        }
    }

    private void splitPlainLines(List<Chunk> result, String text) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(new Chunk(trimmed, "TABLE"));
            }
        }
    }
}
