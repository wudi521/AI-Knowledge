package cn.iocoder.yudao.module.ingestion.parse;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word/Excel/PPT 解析(结构化): OOXML(.docx/.xlsx/.pptx) 用 POI 直接解析——
 * 段落/标题样式层级/表格/内嵌图片; 旧二进制格式(.doc/.xls/.ppt)回退 Tika 纯文本。
 */
@Component
public class OfficeParser implements DocumentParser {

    /** 图片上限(编码后字节数, 超限跳过, 保护视觉模型载荷) */
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Pattern HEADING_LEVEL = Pattern.compile("(?:Heading|标题)\\s*(\\d)");

    private final Tika tika = new Tika();

    @Override
    public String parse(String filePath, String docType) throws Exception {
        return parseStructured(filePath, docType).toPlainText();
    }

    @Override
    public ParsedDocument parseStructured(String filePath, String docType) throws Exception {
        ParsedDocument doc = new ParsedDocument();
        doc.setDocType(docType);
        String lower = filePath.toLowerCase();
        if ("WORD".equalsIgnoreCase(docType) && lower.endsWith(".docx")) {
            parseWord(doc, filePath);
        } else if ("EXCEL".equalsIgnoreCase(docType) && lower.endsWith(".xlsx")) {
            parseExcel(doc, filePath);
        } else if ("PPT".equalsIgnoreCase(docType) && lower.endsWith(".pptx")) {
            parsePpt(doc, filePath);
        } else {
            // 旧格式回退 Tika 纯文本
            return ParsedDocument.ofText(tika.parseToString(new File(filePath)));
        }
        return doc;
    }

    // ========== Word(.docx) ==========

    private void parseWord(ParsedDocument doc, String filePath) {
        try (XWPFDocument xwpf = new XWPFDocument(new FileInputStream(filePath))) {
            for (IBodyElement element : xwpf.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    addWordParagraph(doc, p);
                } else if (element instanceof XWPFTable table) {
                    addTable(doc, tableToMatrix(table));
                }
            }
        } catch (Exception e) {
            // 结构解析失败回退 Tika
            fallback(doc, filePath);
        }
    }

    private void addWordParagraph(ParsedDocument doc, XWPFParagraph p) {
        String text = p.getText() == null ? "" : p.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        int level = headingLevel(p.getStyle());
        if (level > 0) {
            ParsedDocument.HeadingElement h = new ParsedDocument.HeadingElement(text, level);
            doc.getElements().add(h);
        } else {
            doc.getElements().add(new ParsedDocument.ParagraphElement(text));
        }
        // 段落内嵌图片(尽力而为)
        try {
            if (p.getRuns() != null) {
                for (var run : p.getRuns()) {
                    if (run.getEmbeddedPictures() != null) {
                        for (var picture : run.getEmbeddedPictures()) {
                            addImage(doc, picture.getPictureData().getData(),
                                    picture.getPictureData().suggestFileExtension());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 图片提取失败不阻断
        }
    }

    /** 样式 → 标题层级(Heading1/标题 1 等; 非标题返回 0) */
    private int headingLevel(String style) {
        if (StrUtil.isBlank(style)) {
            return 0;
        }
        Matcher m = HEADING_LEVEL.matcher(style);
        if (m.find()) {
            return Math.min(6, Math.max(1, Integer.parseInt(m.group(1))));
        }
        return 0;
    }

    private List<List<String>> tableToMatrix(XWPFTable table) {
        List<List<String>> matrix = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText() == null ? "" : cell.getText().trim());
            }
            matrix.add(cells);
        }
        return matrix;
    }

    // ========== Excel(.xlsx) ==========

    private void parseExcel(ParsedDocument doc, String filePath) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new FileInputStream(filePath))) {
            workbook.forEach(sheet -> {
                List<List<String>> matrix = new ArrayList<>();
                for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                    var row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                        var cell = row.getCell(c);
                        cells.add(cell == null ? "" : String.valueOf(cell).trim());
                    }
                    matrix.add(cells);
                }
                if (!matrix.isEmpty()) {
                    // 表头 = 首行, 其余为数据行
                    addTable(doc, new ArrayList<>(matrix.get(0)), matrix.subList(1, matrix.size()));
                }
            });
        } catch (Exception e) {
            fallback(doc, filePath);
        }
    }

    // ========== PPT(.pptx) ==========

    private void parsePpt(ParsedDocument doc, String filePath) {
        try (XMLSlideShow ppt = new XMLSlideShow(new FileInputStream(filePath))) {
            int slideNo = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                slideNo++;
                List<ParsedDocument.Element> slideElements = new ArrayList<>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String t = textShape.getText() == null ? "" : textShape.getText().trim();
                        if (!t.isEmpty()) {
                            ParsedDocument.ParagraphElement p = new ParsedDocument.ParagraphElement(t);
                            p.setPage(slideNo);
                            slideElements.add(p);
                        }
                    } else if (shape instanceof XSLFPictureShape picShape) {
                        XSLFPictureData data = picShape.getPictureData();
                        if (data != null) {
                            addImage(doc, data.getData(), data.suggestFileExtension());
                        }
                    }
                }
                doc.getElements().addAll(slideElements);
            }
        } catch (Exception e) {
            fallback(doc, filePath);
        }
    }

    // ========== 公共 ==========

    private void addTable(ParsedDocument doc, List<List<String>> matrix) {
        if (matrix.isEmpty()) {
            return;
        }
        addTable(doc, new ArrayList<>(matrix.get(0)), matrix.subList(1, matrix.size()));
    }

    private void addTable(ParsedDocument doc, List<String> header, List<List<String>> rows) {
        ParsedDocument.TableElement table = new ParsedDocument.TableElement(header, rows);
        doc.getElements().add(table);
    }

    /** 图片字节 → ImageElement(超限/编码失败跳过) */
    private void addImage(ParsedDocument doc, byte[] data, String ext) {
        if (data == null || data.length == 0 || data.length > MAX_IMAGE_BYTES) {
            return;
        }
        String mime = "png".equalsIgnoreCase(ext) ? "image/png"
                : "jpg".equalsIgnoreCase(ext) || "jpeg".equalsIgnoreCase(ext) ? "image/jpeg" : "image/png";
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
        doc.getElements().add(new ParsedDocument.ImageElement(dataUrl));
    }

    /** 解析异常时回退 Tika 纯文本(结构化损失但内容不丢) */
    private void fallback(ParsedDocument doc, String filePath) {
        try {
            ParsedDocument plain = ParsedDocument.ofText(tika.parseToString(new File(filePath)));
            doc.getElements().clear();
            doc.getElements().addAll(plain.getElements());
        } catch (Exception ignored) {
            // 双失败留空, 由上层标记 FAILED
        }
    }
}
