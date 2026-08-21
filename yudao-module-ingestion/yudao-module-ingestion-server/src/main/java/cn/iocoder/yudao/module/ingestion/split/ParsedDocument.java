package cn.iocoder.yudao.module.ingestion.split;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化文档(解析层产物): 按阅读顺序保存元素列表, 每个元素带页码/父标题链。
 * <p>
 * 由各解析器产出(MinerU/POI/文本), 切分策略基于结构树切分而非纯文本正则。
 * B1 阶段解析层仍输出纯文本时, 可用 {@link #ofText(String)} 构造无结构文档兜底。
 */
public class ParsedDocument {

    /** 文档名 */
    private String docName;
    /** 文档类型: TXT/MD/PDF/WORD/EXCEL/PPT/IMAGE */
    private String docType;
    /** 元素列表(按阅读顺序) */
    private List<Element> elements = new ArrayList<>();

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public List<Element> getElements() {
        return elements;
    }

    public void setElements(List<Element> elements) {
        this.elements = elements == null ? new ArrayList<>() : elements;
    }

    /**
     * 文档元素(统一接口: 各实现提供文本表示/页码/父标题链)
     */
    public interface Element {

        /** 元素的文本表示(切分与向量化的内容来源) */
        String text();

        /** 页码(未知返回 -1) */
        int page();

        /** 父标题链(如 ["3.2 权利要求书", "3.2.1 装置"]; 无则空列表) */
        List<String> titleChain();

        /** 元素类型标识(SEMANTIC/TABLE/IMAGE/...) */
        default String elementType() {
            return "SEMANTIC";
        }
    }

    /** 标题元素 */
    public static class HeadingElement implements Element {
        private int level;
        private String text;
        private int page = -1;
        private List<String> titleChain = new ArrayList<>();

        public HeadingElement(String text, int level) {
            this.text = text;
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public void setText(String text) {
            this.text = text;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public void setTitleChain(List<String> titleChain) {
            this.titleChain = titleChain == null ? new ArrayList<>() : titleChain;
        }

        @Override
        public String text() {
            return text == null ? "" : text;
        }

        @Override
        public int page() {
            return page;
        }

        @Override
        public List<String> titleChain() {
            return titleChain;
        }
    }

    /** 段落元素 */
    public static class ParagraphElement implements Element {
        private String text;
        private int page = -1;
        private List<String> titleChain = new ArrayList<>();

        public ParagraphElement(String text) {
            this.text = text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public void setTitleChain(List<String> titleChain) {
            this.titleChain = titleChain == null ? new ArrayList<>() : titleChain;
        }

        @Override
        public String text() {
            return text == null ? "" : text;
        }

        @Override
        public int page() {
            return page;
        }

        @Override
        public List<String> titleChain() {
            return titleChain;
        }
    }

    /** 表格元素 */
    public static class TableElement implements Element {
        private List<String> header = new ArrayList<>();
        private List<List<String>> rows = new ArrayList<>();
        private int page = -1;
        private List<String> titleChain = new ArrayList<>();

        public TableElement(List<String> header, List<List<String>> rows) {
            if (header != null) {
                this.header = header;
            }
            if (rows != null) {
                this.rows = rows;
            }
        }

        public List<String> getHeader() {
            return header;
        }

        public List<List<String>> getRows() {
            return rows;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public void setTitleChain(List<String> titleChain) {
            this.titleChain = titleChain == null ? new ArrayList<>() : titleChain;
        }

        @Override
        public String text() {
            StringBuilder sb = new StringBuilder();
            if (!header.isEmpty()) {
                sb.append("表头: ").append(String.join(" | ", header)).append('\n');
            }
            for (List<String> row : rows) {
                sb.append("行: ").append(String.join(" | ", row)).append('\n');
            }
            return sb.toString();
        }

        @Override
        public int page() {
            return page;
        }

        @Override
        public List<String> titleChain() {
            return titleChain;
        }

        @Override
        public String elementType() {
            return "TABLE";
        }
    }

    /** 图片元素(描述文本 + 引用; 描述由视觉模型生成, 缺失时仅上下文占位) */
    public static class ImageElement implements Element {
        private String imageRef;
        private String caption;
        private String contextBefore;
        private String description;
        private int page = -1;
        private List<String> titleChain = new ArrayList<>();

        public ImageElement(String imageRef) {
            this.imageRef = imageRef;
        }

        public String getImageRef() {
            return imageRef;
        }

        public String getCaption() {
            return caption;
        }

        public void setCaption(String caption) {
            this.caption = caption;
        }

        public String getContextBefore() {
            return contextBefore;
        }

        public void setContextBefore(String contextBefore) {
            this.contextBefore = contextBefore;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public void setTitleChain(List<String> titleChain) {
            this.titleChain = titleChain == null ? new ArrayList<>() : titleChain;
        }

        @Override
        public String text() {
            StringBuilder sb = new StringBuilder("[图片]");
            if (StrUtil.isNotBlank(description)) {
                sb.append(' ').append(description);
            } else if (StrUtil.isNotBlank(caption)) {
                sb.append(' ').append(caption);
            }
            if (StrUtil.isNotBlank(contextBefore)) {
                sb.append(" (上下文: ").append(contextBefore).append(')');
            }
            if (!titleChain.isEmpty()) {
                sb.append(" (来源: ").append(String.join(" > ", titleChain)).append(')');
            }
            if (page > 0) {
                sb.append(" (第").append(page).append("页)");
            }
            return sb.toString();
        }

        @Override
        public int page() {
            return page;
        }

        @Override
        public List<String> titleChain() {
            return titleChain;
        }

        @Override
        public String elementType() {
            return "IMAGE";
        }
    }

    /** 列表元素 */
    public static class ListElement implements Element {
        private List<String> items = new ArrayList<>();
        private int page = -1;
        private List<String> titleChain = new ArrayList<>();

        public ListElement(List<String> items) {
            if (items != null) {
                this.items = items;
            }
        }

        public List<String> getItems() {
            return items;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public void setTitleChain(List<String> titleChain) {
            this.titleChain = titleChain == null ? new ArrayList<>() : titleChain;
        }

        @Override
        public String text() {
            return String.join("\n", items);
        }

        @Override
        public int page() {
            return page;
        }

        @Override
        public List<String> titleChain() {
            return titleChain;
        }
    }

    /**
     * 由纯文本构造无结构文档(B1 兜底): 按空行拆段落, 无标题层级。
     */
    public static ParsedDocument ofText(String text) {
        ParsedDocument doc = new ParsedDocument();
        if (text == null || text.isBlank()) {
            return doc;
        }
        for (String para : text.split("\\n\\s*\\n")) {
            String trimmed = para.trim();
            if (!trimmed.isEmpty()) {
                doc.getElements().add(new ParagraphElement(trimmed));
            }
        }
        return doc;
    }

    /** 合并全部元素为纯文本(切分器文本兜底用) */
    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        for (Element e : elements) {
            String t = e.text();
            if (StrUtil.isNotBlank(t)) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /** 元素数(空文档判断用) */
    public boolean isEmpty() {
        return elements == null || elements.isEmpty();
    }
}
