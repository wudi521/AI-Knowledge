package cn.iocoder.yudao.module.ingestion.parse;

import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;

/**
 * 文档解析器: 把原始文档解析为结构化文档(ParsedDocument 结构树)
 * <p>
 * 结构化解析产出 标题层级/段落/表格/图片 元素, 是切分精准度与上下文连贯性的数据基础。
 * 未覆盖 parseStructured 的解析器默认把纯文本包装为无结构文档。
 */
public interface DocumentParser {

    /**
     * 解析文档为纯文本(兼容入口; 结构化解析器可同时覆盖 parseStructured)
     *
     * @param filePath 文档路径(MinIO 下载后的本地临时文件)
     * @param docType  文档类型: TXT/MD/PDF/WORD/EXCEL/PPT/IMAGE
     * @return 解析出的纯文本
     */
    String parse(String filePath, String docType) throws Exception;

    /**
     * 解析文档为结构化文档(默认: 纯文本 → 无结构 ParsedDocument 兜底)
     */
    default ParsedDocument parseStructured(String filePath, String docType) throws Exception {
        return ParsedDocument.ofText(parse(filePath, docType));
    }

}
