package cn.iocoder.yudao.module.ingestion.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * PDF 解析: 优先提取文本层;无文本层(扫描件)由调用方走 OCR
 */
@Component
public class PdfParser implements DocumentParser {

    @Override
    public String parse(String filePath, String docType) throws Exception {
        try (PDDocument document = Loader.loadPDF(new File(filePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            // 若提取不到有效文本, 返回空, 由上层决定走 OCR
            return text == null ? "" : text.trim();
        }
    }

}
