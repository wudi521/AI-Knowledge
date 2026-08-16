package cn.iocoder.yudao.module.ingestion.parse;

/**
 * 文档解析器: 把原始文档解析为纯文本
 */
public interface DocumentParser {

    /**
     * 解析文档内容
     *
     * @param filePath 文档路径(MinIO 下载后的本地临时文件)
     * @param docType 文档类型: TXT/MD/PDF/WORD/EXCEL/PPT/IMAGE
     * @return 解析出的纯文本
     */
    String parse(String filePath, String docType) throws Exception;

}
