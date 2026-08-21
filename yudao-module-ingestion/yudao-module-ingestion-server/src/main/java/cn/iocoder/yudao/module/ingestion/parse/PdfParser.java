package cn.iocoder.yudao.module.ingestion.parse;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Base64;
import java.util.List;

/**
 * PDF 解析(结构化): 按页提取文本块 + 提取内嵌图片(转 base64 data URL, 供视觉模型理解)。
 * 无文本层(扫描件)由 MinerU/视觉模型兜底; 图片过大(>5MB)跳过, 避免 API 载荷超限。
 */
@Component
public class PdfParser implements DocumentParser {

    /** 图片上限: 编码后超过该字节数跳过(视觉模型载荷保护) */
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    @Override
    public String parse(String filePath, String docType) throws Exception {
        return parseStructured(filePath, docType).toPlainText();
    }

    @Override
    public ParsedDocument parseStructured(String filePath, String docType) throws Exception {
        ParsedDocument doc = new ParsedDocument();
        doc.setDocType("PDF");
        try (PDDocument document = Loader.loadPDF(new File(filePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int pages = document.getNumberOfPages();
            for (int pageNo = 1; pageNo <= pages; pageNo++) {
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String pageText = stripper.getText(document);
                addPageParagraphs(doc, pageText, pageNo);
                extractPageImages(doc, document.getPage(pageNo - 1), pageNo);
            }
        }
        return doc;
    }

    /** 页文本 → 段落元素(按空行分段; 超长行原样保留, 由切分器处理) */
    private void addPageParagraphs(ParsedDocument doc, String pageText, int pageNo) {
        if (StrUtil.isBlank(pageText)) {
            return;
        }
        for (String para : pageText.split("\\n\\s*\\n")) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ParsedDocument.ParagraphElement p = new ParsedDocument.ParagraphElement(trimmed);
            p.setPage(pageNo);
            doc.getElements().add(p);
        }
    }

    /** 页内图片 → ImageElement(按资源遍历, 过大的跳过) */
    private void extractPageImages(ParsedDocument doc, PDPage page, int pageNo) {
        try {
            org.apache.pdfbox.pdmodel.PDResources resources = page.getResources();
            if (resources == null) {
                return;
            }
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobject = resources.getXObject(name);
                if (!(xobject instanceof PDImageXObject image)) {
                    continue;
                }
                String dataUrl = toBase64DataUrl(image);
                if (dataUrl == null) {
                    continue;
                }
                ParsedDocument.ImageElement img = new ParsedDocument.ImageElement(dataUrl);
                img.setPage(pageNo);
                doc.getElements().add(img);
            }
        } catch (Exception e) {
            // 图片提取失败不阻断文本解析
            cn.hutool.log.LogFactory.get().warn("[extractPageImages][第 {} 页图片提取失败: {}]", pageNo, e.getMessage());
        }
    }

    /** PDImageXObject → base64 PNG data URL; 超限/编码失败返回 null */
    private String toBase64DataUrl(PDImageXObject image) {
        try {
            BufferedImage bim = image.getImage();
            if (bim == null) {
                return null;
            }
            double maxSide = Math.max(bim.getWidth(), bim.getHeight());
            double scale = Math.min(1.0, 2000.0 / maxSide);
            int w = Math.max(1, (int) (bim.getWidth() * scale));
            int h = Math.max(1, (int) (bim.getHeight() * scale));
            if (w != bim.getWidth() || h != bim.getHeight()) {
                java.awt.Image scaled = bim.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH);
                BufferedImage tmp = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                tmp.getGraphics().drawImage(scaled, 0, 0, null);
                bim = tmp;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bim, "png", baos);
            byte[] bytes = baos.toByteArray();
            if (bytes.length > MAX_IMAGE_BYTES) {
                return null;
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }
}
