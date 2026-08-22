package cn.iocoder.yudao.module.ingestion.domain.patent;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.ingestion.split.SplitParams;
import cn.iocoder.yudao.module.ingestion.split.SplitUtils;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 专利切分器:
 * <ul>
 *   <li>章节识别(权利要求书/说明书/技术领域/背景技术/发明内容/附图说明/具体实施方式/摘要/著录);</li>
 *   <li>一条权利要求 = 一个完整 Chunk(可超过普通 maxTokens, 不允许截断);</li>
 *   <li>说明书按章节切分, 章节内段落合并到 400~700 tokens, overlap 仅同章节内;</li>
 *   <li>每个 Chunk 带结构化搜索头([专利名称]/[申请号]/[公布号]/[章节]/[权利要求]/[页码]) + metadata JSON。</li>
 * </ul>
 */
public class PatentSplitter {

    /** 章节类型(任务书 6.1) */
    public static final String SEC_BIBLIOGRAPHIC = "BIBLIOGRAPHIC";
    public static final String SEC_ABSTRACT = "ABSTRACT";
    public static final String SEC_CLAIMS = "CLAIMS";
    public static final String SEC_TECHNICAL_FIELD = "TECHNICAL_FIELD";
    public static final String SEC_BACKGROUND = "BACKGROUND";
    public static final String SEC_INVENTION_SUMMARY = "INVENTION_SUMMARY";
    public static final String SEC_DRAWING_DESCRIPTION = "DRAWING_DESCRIPTION";
    public static final String SEC_EMBODIMENT = "EMBODIMENT";
    public static final String SEC_DRAWING = "DRAWING";
    public static final String SEC_OTHER = "OTHER";

    /** 说明书目标 token 范围 */
    private static final int TARGET_MIN_TOKENS = 400;
    private static final int TARGET_MAX_TOKENS = 700;

    private final PatentClaimParser claimParser = new PatentClaimParser();

    /** 章节标题 → 类型(按出现顺序匹配) */
    private static final Map<String, String> SECTION_TITLES = buildSectionTitles();

    private static Map<String, String> buildSectionTitles() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("权利要求书", SEC_CLAIMS);
        map.put("技术领域", SEC_TECHNICAL_FIELD);
        map.put("背景技术", SEC_BACKGROUND);
        map.put("发明内容", SEC_INVENTION_SUMMARY);
        map.put("附图说明", SEC_DRAWING_DESCRIPTION);
        map.put("具体实施方式", SEC_EMBODIMENT);
        map.put("说明书", SEC_EMBODIMENT);
        map.put("摘要", SEC_ABSTRACT);
        return map;
    }

    /**
     * 专利文档切分
     *
     * @param doc     结构化文档
     * @param params  切分参数(maxTokens 仅约束说明书段落, 权利要求不受限)
     * @param metadata 著录信息(搜索头/metadata 用)
     */
    public List<Chunk> split(ParsedDocument doc, SplitParams params, PatentMetadata metadata) {
        List<Chunk> result = new ArrayList<>();
        if (doc == null || doc.isEmpty()) {
            return result;
        }
        // 1. 按章节分组(元素索引范围)
        List<Section> sections = detectSections(doc);
        int maxTokens = params == null ? 500 : params.getMaxTokens();
        for (Section section : sections) {
            List<ParsedDocument.Element> elements = doc.getElements().subList(section.start, section.end);
            if (SEC_CLAIMS.equals(section.type)) {
                result.addAll(splitClaims(elements, metadata));
            } else if (SEC_BIBLIOGRAPHIC.equals(section.type)) {
                result.addAll(splitBibliographic(elements, metadata));
            } else {
                result.addAll(splitDescription(section, elements, metadata, maxTokens));
            }
        }
        return result;
    }

    // ========== 章节识别 ==========

    private record Section(String type, String title, int start, int end) {
    }

    private List<Section> detectSections(ParsedDocument doc) {
        List<Section> sections = new ArrayList<>();
        int currentStart = 0;
        String currentType = SEC_BIBLIOGRAPHIC;
        String currentTitle = "著录信息";
        for (int i = 0; i < doc.getElements().size(); i++) {
            String text = doc.getElements().get(i).text();
            String type = matchSection(text);
            if (type != null && !type.equals(currentType)) {
                sections.add(new Section(currentType, currentTitle, currentStart, i));
                currentStart = i;
                currentType = type;
                currentTitle = StrUtil.maxLength(text, 50);
            }
        }
        sections.add(new Section(currentType, currentTitle, currentStart, doc.getElements().size()));
        return sections;
    }

    private String matchSection(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        // 去全部空白(兼容 PDF 全角空格标题: 权   利   要   求   书)
        String compact = t.replaceAll("\\s+", "");
        // ① 章节页标题(章节名 + 斜杠页码: 权利要求书 1/1 页 / 页眉+章节名): 是章节边界
        if (compact.matches(".*[0-9]+/[0-9]+页.*")) {
            for (Map.Entry<String, String> e : SECTION_TITLES.entrySet()) {
                if (compact.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            return null;
        }
        // ② 页数汇总行(权利要求书1页 说明书2页 附图1页)不是章节边界
        if (compact.matches(".*[0-9]+页.*")) {
            return null;
        }
        // ③ 纯章节标题行
        for (Map.Entry<String, String> e : SECTION_TITLES.entrySet()) {
            if (compact.startsWith(e.getKey()) || compact.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    // ========== 权利要求 ==========

    private List<Chunk> splitClaims(List<ParsedDocument.Element> elements, PatentMetadata metadata) {
        List<Chunk> result = new ArrayList<>();
        StringBuilder claimsText = new StringBuilder();
        for (ParsedDocument.Element e : elements) {
            claimsText.append(e.text()).append('\n');
        }
        List<PatentClaimParser.PatentClaim> claims = claimParser.parse(claimsText.toString());
        if (claims.isEmpty()) {
            // 解析失败: 整节作为描述块(不丢内容)
            return splitDescription(new Section(SEC_CLAIMS, "权利要求书", 0, elements.size()), elements, metadata, 500);
        }
        for (PatentClaimParser.PatentClaim claim : claims) {
            Chunk chunk = new Chunk(buildSearchHead(metadata, "权利要求书", String.valueOf(claim.getClaimNo()))
                    + "\n" + claim.getText(), "PATENT_CLAIM");
            chunk.setChunkRole("LEAF");
            chunk.setSectionPath("权利要求书");
            chunk.setMetadata(JSONUtil.toJsonStr(claimMetadata(metadata, SEC_CLAIMS, "权利要求书",
                    claim.getClaimNo(), claim.getClaimType(), claim.getDependsOn())));
            result.add(chunk);
        }
        return result;
    }

    // ========== 著录信息 ==========

    private List<Chunk> splitBibliographic(List<ParsedDocument.Element> elements, PatentMetadata metadata) {
        List<Chunk> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (ParsedDocument.Element e : elements) {
            text.append(e.text()).append('\n');
        }
        if (text.length() == 0) {
            return result;
        }
        Chunk chunk = new Chunk(buildSearchHead(metadata, "著录信息", null) + "\n" + text, "PATENT_BIBLIO");
        chunk.setChunkRole("LEAF");
        chunk.setSectionPath("著录信息");
        chunk.setMetadata(JSONUtil.toJsonStr(claimMetadata(metadata, SEC_BIBLIOGRAPHIC, "著录信息", null, null, null)));
        result.add(chunk);
        return result;
    }

    // ========== 说明书(章节内段落合并) ==========

    private List<Chunk> splitDescription(Section section, List<ParsedDocument.Element> elements,
                                         PatentMetadata metadata, int maxTokens) {
        List<Chunk> result = new ArrayList<>();
        int targetTokens = Math.max(TARGET_MIN_TOKENS, Math.min(TARGET_MAX_TOKENS, Math.max(maxTokens, 400)));
        StringBuilder current = new StringBuilder();
        int pageStart = -1;
        int pageEnd = -1;
        for (ParsedDocument.Element e : elements) {
            String t = e.text();
            if (StrUtil.isBlank(t)) {
                continue;
            }
            // 章节标题行本身作为首段(保留章节上下文)
            if (matchSection(t) != null) {
                continue;
            }
            if (current.length() > 0 && SplitUtils.estimateTokens(current.toString()) + SplitUtils.estimateTokens(t) > targetTokens) {
                result.add(descriptionChunk(section, current.toString(), metadata, pageStart, pageEnd));
                current.setLength(0);
                pageStart = -1;
                pageEnd = -1;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(t);
            if (e.page() > 0) {
                if (pageStart < 0) {
                    pageStart = e.page();
                }
                pageEnd = Math.max(pageEnd, e.page());
            }
        }
        if (current.length() > 0) {
            result.add(descriptionChunk(section, current.toString(), metadata, pageStart, pageEnd));
        }
        return result;
    }

    private Chunk descriptionChunk(Section section, String text, PatentMetadata metadata, int pageStart, int pageEnd) {
        String chunkType = switch (section.type) {
            case SEC_ABSTRACT -> "PATENT_ABSTRACT";
            case SEC_DRAWING -> "PATENT_DRAWING";
            default -> "PATENT_DESCRIPTION";
        };
        Chunk chunk = new Chunk(buildSearchHead(metadata, section.title, null) + "\n" + text, chunkType);
        chunk.setChunkRole("LEAF");
        chunk.setSectionPath(section.title);
        chunk.setSourcePageStart(pageStart < 0 ? -1 : pageStart);
        chunk.setSourcePageEnd(pageEnd);
        chunk.setMetadata(JSONUtil.toJsonStr(claimMetadata(metadata, section.type, section.title, null, null, null)));
        return chunk;
    }

    // ========== 搜索头与 metadata ==========

    private String buildSearchHead(PatentMetadata metadata, String sectionTitle, String claimNo) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(metadata.getTitle())) {
            sb.append("[专利名称] ").append(metadata.getTitle()).append('\n');
        }
        if (StrUtil.isNotBlank(metadata.getApplicationNo())) {
            sb.append("[申请号] ").append(metadata.getApplicationNo()).append('\n');
        }
        if (StrUtil.isNotBlank(metadata.getPublicationNo())) {
            sb.append("[公布号] ").append(metadata.getPublicationNo()).append('\n');
        }
        sb.append("[章节] ").append(sectionTitle).append('\n');
        if (StrUtil.isNotBlank(claimNo)) {
            sb.append("[权利要求] ").append(claimNo).append('\n');
        }
        return sb.toString().trim();
    }

    private Map<String, Object> claimMetadata(PatentMetadata metadata, String sectionType, String sectionTitle,
                                              Integer claimNo, String claimType, List<Integer> dependsOn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domainCode", "PATENT");
        if (metadata != null) {
            m.put("applicationNo", metadata.getApplicationNo());
            m.put("publicationNo", metadata.getPublicationNo());
            m.put("title", metadata.getTitle());
        }
        m.put("sectionType", sectionType);
        m.put("sectionTitle", sectionTitle);
        if (claimNo != null) {
            m.put("claimNo", claimNo);
        }
        if (claimType != null) {
            m.put("claimType", claimType);
        }
        if (dependsOn != null) {
            m.put("dependsOn", dependsOn);
        }
        m.put("extractorVersion", "patent-mvp-1.0");
        return m;
    }
}
