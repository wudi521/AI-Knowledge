package cn.iocoder.yudao.module.ingestion.parse;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MinerU 结构化结果 → ParsedDocument 转换器。
 * 输入: MinerU JSON 数组(每页一个元素: page_idx / md / imgs 等), 兼容 2.x 常见返回格式。
 * 转换: md 中的 # 标题 → HeadingElement; 段落 → ParagraphElement; 图片行(![]) → 占位说明(图片实体由 MinerU 侧持有, 不参与向量化)。
 */
@Slf4j
public final class MineruJsonConverter {

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[.*?]\\(.*?\\)");

    private MineruJsonConverter() {
    }

    /**
     * @param pages MinerU data 数组(可能为 JSONArray 或 JSONObject 包装)
     * @param docType 文档类型
     */
    public static ParsedDocument convert(Object pages, String docType) {
        ParsedDocument doc = new ParsedDocument();
        doc.setDocType(docType == null ? "PDF" : docType);
        if (pages == null) {
            return doc;
        }
        JSONArray arr = pages instanceof JSONArray a ? a : JSONUtil.parseArray(JSONUtil.toJsonStr(pages));
        for (Object o : arr) {
            if (!(o instanceof JSONObject pageObj)) {
                continue;
            }
            int pageIdx = pageObj.getInt("page_idx", 0) + 1;
            String md = pageObj.getStr("md");
            if (md == null || md.isBlank()) {
                continue;
            }
            parseMarkdown(doc, md, pageIdx);
        }
        return doc;
    }

    private static void parseMarkdown(ParsedDocument doc, String md, int pageIdx) {
        StringBuilder para = new StringBuilder();
        for (String line : md.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flush(doc, para, pageIdx);
                continue;
            }
            Matcher h = MD_HEADING.matcher(trimmed);
            if (h.matches()) {
                flush(doc, para, pageIdx);
                ParsedDocument.HeadingElement heading = new ParsedDocument.HeadingElement(h.group(2).trim(), h.group(1).length());
                heading.setPage(pageIdx);
                doc.getElements().add(heading);
                continue;
            }
            // 图片行: 替换为占位说明(图片内容由 MinerU 输出, 不随文本块向量化)
            if (MD_IMAGE.matcher(trimmed).find() && trimmed.startsWith("!")) {
                flush(doc, para, pageIdx);
                ParsedDocument.ParagraphElement imgNote = new ParsedDocument.ParagraphElement("[图片] 见第" + pageIdx + "页(图片内容由文档解析服务处理)");
                imgNote.setPage(pageIdx);
                doc.getElements().add(imgNote);
                continue;
            }
            if (para.length() > 0) {
                para.append('\n');
            }
            para.append(trimmed);
        }
        flush(doc, para, pageIdx);
    }

    private static void flush(ParsedDocument doc, StringBuilder para, int pageIdx) {
        String trimmed = para.toString().trim();
        if (!trimmed.isEmpty()) {
            ParsedDocument.ParagraphElement p = new ParsedDocument.ParagraphElement(trimmed);
            p.setPage(pageIdx);
            doc.getElements().add(p);
        }
        para.setLength(0);
    }
}
