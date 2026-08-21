package cn.iocoder.yudao.module.ingestion.parse;

import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * IMAGE 文档解析(结构化): 整图 → 视觉模型生成描述(含 OCR 文字) → 单个 ImageElement。
 * 视觉模型不可用/失败 → 仅上下文占位块(仍可被章节检索命中), 不阻断入库。
 */
@Component
public class ImageParser implements DocumentParser {

    @Resource
    private ImageProcessor imageProcessor;

    @Override
    public String parse(String filePath, String docType) {
        return "";
    }

    @Override
    public ParsedDocument parseStructured(String filePath, String docType) {
        ParsedDocument doc = new ParsedDocument();
        doc.setDocType("IMAGE");
        String dataUrl = TextParser.fileToDataUrl(new File(filePath));
        if (dataUrl == null) {
            return doc; // 读图失败 → 空文档(入库为空片段, 由上层标记失败)
        }
        ParsedDocument.ImageElement img = new ParsedDocument.ImageElement(dataUrl);
        if (imageProcessor.isEnabled()) {
            img.setDescription(imageProcessor.describeImage(dataUrl, null));
        }
        doc.getElements().add(img);
        return doc;
    }
}
