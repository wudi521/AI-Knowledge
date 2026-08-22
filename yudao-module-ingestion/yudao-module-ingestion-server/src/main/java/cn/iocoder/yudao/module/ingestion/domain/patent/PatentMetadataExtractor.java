package cn.iocoder.yudao.module.ingestion.domain.patent;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 专利著录信息提取器(规则优先: 结构标题 + 正则; 缺失字段为 null/空, 禁止猜测)。
 * 兼容: 数字间空格(202311344028 .2)、IPC 空格(H04N 21/238)、PDF 换行断裂、全角标点。
 * <p>
 * 输入为 PDF 提取的文本(可能含著录页/页眉), 提取时按 (xx) 字段标记定位。
 */
@Slf4j
public class PatentMetadataExtractor {

    // 字段标记: (21)申请号 / (22)申请日 / (71)申请人 / (72)发明人 / (51)Int.Cl. / (54)发明名称 / (57)摘要 / (10)申请公布号 / (43)申请公布日
    private static final Pattern FIELD = Pattern.compile(
            "\\((\\d{2})\\)\\s*([^\n]{0,12})");

    private static final Pattern APPLICATION_NO = Pattern.compile("申请号\\s*([0-9\\s.]+)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("申请公布号\\s*(CN\\s*[0-9\\s]+\\s*[A-Z])");
    private static final Pattern FILING_DATE = Pattern.compile("申请日\\s*([0-9\\s.]+)");
    private static final Pattern PUBLICATION_DATE = Pattern.compile("申请公布日\\s*([0-9\\s.]+)");
    private static final Pattern IPC_LINE = Pattern.compile("([A-Z]\\d{1,2}\\s*[A-Z]?\\s*\\d{1,5}(?:/\\s*\\d{1,5})?)");

    /** 提取著录信息(找不到的字段为 null/空列表) */
    public PatentMetadata extract(String text) {
        PatentMetadata meta = new PatentMetadata();
        if (StrUtil.isBlank(text)) {
            return meta;
        }
        // 用 (xx) 标记分段, 再逐字段正则
        String[] sections = text.split("\\(\\d{2}\\)");
        for (String section : sections) {
            String s = section.trim();
            if (s.isEmpty()) {
                continue;
            }
            extractFromSection(meta, s);
        }
        return meta;
    }

    private void extractFromSection(PatentMetadata meta, String section) {
        // 申请号
        if (meta.getApplicationNo() == null) {
            String v = match(APPLICATION_NO, section);
            if (v != null) {
                meta.setApplicationNo(normalizeNumber(v));
            }
        }
        // 公布号
        if (meta.getPublicationNo() == null) {
            String v = match(PUBLICATION_NO, section);
            if (v != null) {
                meta.setPublicationNo(v.replaceAll("\\s+", " ").trim());
            }
        }
        // 申请日 / 公布日
        if (meta.getFilingDate() == null) {
            String v = match(FILING_DATE, section);
            if (v != null) {
                meta.setFilingDate(normalizeDate(v));
            }
        }
        if (meta.getPublicationDate() == null) {
            String v = match(PUBLICATION_DATE, section);
            if (v != null) {
                meta.setPublicationDate(normalizeDate(v));
            }
        }
        // 申请人 / 发明人(可能多行; 取非空行)
        if (section.startsWith("71") || section.contains("申请人")) {
            if (meta.getApplicants().isEmpty()) {
                meta.getApplicants().addAll(extractNames(section, "申请人"));
            }
        }
        if (section.startsWith("72") || section.contains("发明人")) {
            if (meta.getInventors().isEmpty()) {
                meta.getInventors().addAll(extractNames(section, "发明人"));
            }
        }
        // 代理机构/代理师
        if (meta.getAgency() == null && (section.contains("代理机构") || section.startsWith("74"))) {
            String v = firstLineAfter(section, "代理机构");
            if (v != null && !v.contains("(74)")) {
                meta.setAgency(v);
            }
        }
        // IPC
        if (meta.getIpcCodes().isEmpty() && (section.contains("Int") || section.startsWith("51"))) {
            Matcher m = IPC_LINE.matcher(section);
            while (m.find()) {
                String code = m.group(1).replaceAll("\\s+", "").replace("(2011.01)", "");
                if (code.length() > 3 && !meta.getIpcCodes().contains(code)) {
                    meta.getIpcCodes().add(code);
                }
            }
        }
        // 发明名称 / 摘要
        if (meta.getTitle() == null && section.contains("发明名称")) {
            meta.setTitle(cleanLine(firstLineAfter(section, "发明名称")));
        }
        if (meta.getAbstractText() == null && section.contains("摘要")) {
            meta.setAbstractText(cleanLine(StrUtil.subAfter(section, "摘要", false)));
        }
        // 文献类型
        if (meta.getSourceType() == null && section.contains("发明专利申请")) {
            meta.setSourceType("CN_PATENT_APPLICATION_PUBLICATION");
        }
    }

    /** 取标记后的第一行(处理换行断裂: 截断到行尾) */
    private String firstLineAfter(String section, String marker) {
        String after = StrUtil.subAfter(section, marker, false);
        if (after == null) {
            return null;
        }
        int nl = after.indexOf('\n');
        return nl > 0 ? after.substring(0, nl) : after;
    }

    private List<String> extractNames(String section, String marker) {
        List<String> names = new ArrayList<>();
        String after = StrUtil.subAfter(section, marker, false);
        if (after == null) {
            return names;
        }
        // 地址/邮编行排除; 逗号/顿号分隔
        for (String line : after.split("\n")) {
            String t = cleanLine(line);
            if (t.isEmpty() || t.startsWith("地址") || t.matches("\\d{3,6}.*")) {
                continue;
            }
            for (String part : t.split("[、,，;；]")) {
                String p = part.trim();
                if (!p.isEmpty() && !names.contains(p)) {
                    names.add(p);
                }
            }
        }
        return names;
    }

    /** 数字归一化: 202311344028 .2 -> 202311344028.2; 2023 .10 .17 -> 2023-10-17 */
    private String normalizeNumber(String raw) {
        String s = raw.replaceAll("\\s+", "");
        // 申请号: 保留一个点
        return s;
    }

    private String normalizeDate(String raw) {
        String s = raw.replaceAll("\\s+", "").replace('.', '-');
        if (s.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            String[] parts = s.split("-");
            return parts[0] + "-" + pad(parts[1]) + "-" + pad(parts[2]);
        }
        return s;
    }

    private String pad(String s) {
        return s.length() == 1 ? "0" + s : s;
    }

    private String match(Pattern p, String section) {
        Matcher m = p.matcher(section);
        return m.find() ? m.group(1) : null;
    }

    private String cleanLine(String s) {
        return s == null ? "" : s.trim();
    }
}
