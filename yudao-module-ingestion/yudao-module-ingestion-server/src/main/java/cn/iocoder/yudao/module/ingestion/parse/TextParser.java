package cn.iocoder.yudao.module.ingestion.parse;

import cn.hutool.core.io.FileUtil;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT / MD 纯文本解析(结构化): MD 按 # 号识别标题层级; TXT 按空行分段落
 */
@Component
public class TextParser implements DocumentParser {

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    @Override
    public String parse(String filePath, String docType) {
        return FileUtil.readString(filePath, StandardCharsets.UTF_8);
    }

    @Override
    public ParsedDocument parseStructured(String filePath, String docType) {
        ParsedDocument doc = new ParsedDocument();
        doc.setDocType(docType);
        String text = FileUtil.readString(filePath, StandardCharsets.UTF_8);
        if ("MD".equalsIgnoreCase(docType)) {
            parseMarkdown(doc, text);
        } else {
            parsePlain(doc, text);
        }
        return doc;
    }

    /** Markdown: 标题行 → HeadingElement(带层级); 其余按空行分段落 */
    private void parseMarkdown(ParsedDocument doc, String text) {
        StringBuilder para = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            Matcher m = MD_HEADING.matcher(line.trim());
            if (m.matches()) {
                flushPara(doc, para);
                ParsedDocument.HeadingElement h = new ParsedDocument.HeadingElement(m.group(2).trim(), m.group(1).length());
                doc.getElements().add(h);
            } else if (line.trim().isEmpty()) {
                flushPara(doc, para);
            } else {
                if (para.length() > 0) {
                    para.append('\n');
                }
                para.append(line.trim());
            }
        }
        flushPara(doc, para);
    }

    private void parsePlain(ParsedDocument doc, String text) {
        for (String p : text.split("\\n\\s*\\n")) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                doc.getElements().add(new ParsedDocument.ParagraphElement(trimmed));
            }
        }
    }

    private void flushPara(ParsedDocument doc, StringBuilder para) {
        String trimmed = para.toString().trim();
        if (!trimmed.isEmpty()) {
            doc.getElements().add(new ParsedDocument.ParagraphElement(trimmed));
        }
        para.setLength(0);
    }

    /** 图片文档辅助: 文件 → base64 data URL */
    public static String fileToDataUrl(File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String mime = "image/png";
            String name = file.getName() == null ? "" : file.getName().toLowerCase();
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                mime = "image/jpeg";
            } else if (name.endsWith(".gif")) {
                mime = "image/gif";
            } else if (name.endsWith(".webp")) {
                mime = "image/webp";
            } else if (name.endsWith(".bmp")) {
                mime = "image/bmp";
            }
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }
}
