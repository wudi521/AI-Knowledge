package cn.iocoder.yudao.module.ingestion.domain.patent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.ingestion.split.SplitParams;
import cn.iocoder.yudao.module.ingestion.split.SplitUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 专利切分器。 */
public class PatentSplitter {

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

    private static final int TARGET_MIN_TOKENS = 400;
    private static final int TARGET_MAX_TOKENS = 700;
    private static final Pattern CLAIM_START = Pattern.compile("^\\s*(\\d+)\\s*[.．、]", Pattern.MULTILINE);

    private final PatentClaimParser claimParser = new PatentClaimParser();
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

    public List<Chunk> split(ParsedDocument doc, SplitParams params, PatentMetadata metadata) {
        List<Chunk> result = new ArrayList<>();
        if (doc == null || doc.isEmpty()) return result;
        List<Section> sections = detectSections(doc);
        int maxTokens = params == null ? 500 : params.getMaxTokens();
        for (Section section : sections) {
            List<ParsedDocument.Element> elements = doc.getElements().subList(section.start, section.end);
            if (SEC_CLAIMS.equals(section.type)) result.addAll(splitClaims(elements, metadata));
            else if (SEC_BIBLIOGRAPHIC.equals(section.type)) result.addAll(splitBibliographic(elements, metadata));
            else result.addAll(splitDescription(section, elements, metadata, maxTokens));
        }
        return result;
    }

    /**
     * 使用与真实切片完全相同的章节识别口径统计权利要求数量。
     * 这样不会再用独立正则误命中封面“权利要求书1页 说明书2页”的页数汇总行。
     */
    public int countClaims(ParsedDocument doc) {
        if (doc == null || doc.isEmpty()) return 0;
        for (Section section : detectSections(doc)) {
            if (!SEC_CLAIMS.equals(section.type)) continue;
            List<ParsedDocument.Element> elements = doc.getElements().subList(section.start, section.end);
            return parseClaims(elements).size();
        }
        return 0;
    }

    private record Section(String type, String title, int start, int end) {}
    private record PageRange(int start, int end) {}

    private List<Section> detectSections(ParsedDocument doc) {
        List<Section> sections = new ArrayList<>();
        int currentStart = 0;
        String currentType = SEC_BIBLIOGRAPHIC;
        String currentTitle = "著录信息";
        for (int i = 0; i < doc.getElements().size(); i++) {
            ParsedDocument.Element e = doc.getElements().get(i);
            if (e instanceof ParsedDocument.ImageElement) continue;
            String type = matchSection(e.text());
            if (type != null && !type.equals(currentType)) {
                sections.add(new Section(currentType, currentTitle, currentStart, i));
                currentStart = i;
                currentType = type;
                currentTitle = StrUtil.maxLength(e.text(), 50);
            }
        }
        sections.add(new Section(currentType, currentTitle, currentStart, doc.getElements().size()));
        return sections;
    }

    private String matchSection(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return null;
        String compact = t.replaceAll("[\\s\\u3000]+", "");
        // 真实专利章节页常见“权利要求书1/1页”；优先作为章节边界。
        if (compact.matches(".*[0-9]+/[0-9]+页.*")) {
            for (Map.Entry<String, String> e : SECTION_TITLES.entrySet()) {
                if (compact.contains(e.getKey())) return e.getValue();
            }
            return null;
        }
        // 封面常见“权利要求书1页 说明书2页 附图1页”，只是页数汇总，不是章节边界。
        if (compact.matches(".*[0-9]+页.*")) return null;
        for (Map.Entry<String, String> e : SECTION_TITLES.entrySet()) {
            if (compact.startsWith(e.getKey()) || compact.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private List<PatentClaimParser.PatentClaim> parseClaims(List<ParsedDocument.Element> elements) {
        StringBuilder claimsText = new StringBuilder();
        for (ParsedDocument.Element e : elements) {
            // 图片 text() 可能携带 contextBefore，从而重复带入权利要求正文；统计和切片都统一排除。
            if (e instanceof ParsedDocument.ImageElement) continue;
            claimsText.append(e.text()).append('\n');
        }
        return claimParser.parse(claimsText.toString());
    }

    private List<Chunk> splitClaims(List<ParsedDocument.Element> elements, PatentMetadata metadata) {
        List<Chunk> result = new ArrayList<>();
        List<PatentClaimParser.PatentClaim> claims = parseClaims(elements);
        if (claims.isEmpty()) {
            return splitDescription(new Section(SEC_CLAIMS, "权利要求书", 0, elements.size()), elements, metadata, 500);
        }

        Map<Integer, PageRange> pageRanges = resolveClaimPageRanges(elements);
        for (PatentClaimParser.PatentClaim claim : claims) {
            PageRange range = pageRanges.get(claim.getClaimNo());
            int pageStart = range != null ? range.start : -1;
            int pageEnd = range != null ? range.end : -1;
            Chunk chunk = new Chunk(buildSearchHead(metadata, "权利要求书", String.valueOf(claim.getClaimNo()), pageStart, pageEnd)
                    + "\n" + claim.getText(), "PATENT_CLAIM");
            chunk.setChunkRole("LEAF");
            chunk.setSectionPath("权利要求书");
            if (pageStart > 0) chunk.setSourcePageStart(pageStart);
            if (pageEnd > 0) chunk.setSourcePageEnd(pageEnd);
            chunk.setMetadata(JSONUtil.toJsonStr(claimMetadata(metadata, SEC_CLAIMS, "权利要求书",
                    claim.getClaimNo(), claim.getClaimType(), claim.getDependsOn(), pageStart, pageEnd)));
            result.add(chunk);
        }
        return result;
    }

    private Map<Integer, PageRange> resolveClaimPageRanges(List<ParsedDocument.Element> elements) {
        Map<Integer, int[]> mutable = new LinkedHashMap<>();
        Integer currentClaim = null;
        for (ParsedDocument.Element element : elements) {
            if (element instanceof ParsedDocument.ImageElement) continue;
            String text = element.text() == null ? "" : element.text();
            for (String line : text.split("\\R")) {
                Matcher matcher = CLAIM_START.matcher(line);
                if (matcher.find()) currentClaim = Integer.parseInt(matcher.group(1));
                if (currentClaim != null && element.page() > 0) {
                    int[] range = mutable.computeIfAbsent(currentClaim, k -> new int[]{element.page(), element.page()});
                    range[0] = Math.min(range[0], element.page());
                    range[1] = Math.max(range[1], element.page());
                }
            }
        }
        Map<Integer, PageRange> result = new LinkedHashMap<>();
        mutable.forEach((claimNo, range) -> result.put(claimNo, new PageRange(range[0], range[1])));
        return result;
    }

    private List<Chunk> splitBibliographic(List<ParsedDocument.Element> elements, PatentMetadata metadata) {
        List<Chunk> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int pageStart = -1, pageEnd = -1;
        for (ParsedDocument.Element e : elements) {
            text.append(e.text()).append('\n');
            if (e.page() > 0) {
                if (pageStart < 0) pageStart = e.page();
                pageEnd = Math.max(pageEnd, e.page());
            }
        }
        if (text.length() == 0) return result;
        Chunk chunk = new Chunk(buildSearchHead(metadata, "著录信息", null, pageStart, pageEnd) + "\n" + text, "PATENT_BIBLIO");
        chunk.setChunkRole("LEAF");
        chunk.setSectionPath("著录信息");
        if (pageStart > 0) chunk.setSourcePageStart(pageStart);
        if (pageEnd > 0) chunk.setSourcePageEnd(pageEnd);
        chunk.setMetadata(JSONUtil.toJsonStr(claimMetadata(metadata, SEC_BIBLIOGRAPHIC, "著录信息", null, null, null, pageStart, pageEnd)));
        result.add(chunk);
        return result;
    }

    private List<Chunk> splitDescription(Section section, List<ParsedDocument.Element> elements, PatentMetadata metadata, int maxTokens) {
        List<Chunk> result = new ArrayList<>();
        int targetTokens = Math.max(TARGET_MIN_TOKENS, Math.min(TARGET_MAX_TOKENS, Math.max(maxTokens, 400)));
        StringBuilder current = new StringBuilder();
        int pageStart = -1, pageEnd = -1;
        for (ParsedDocument.Element e : elements) {
            String t = e.text();
            if (StrUtil.isBlank(t)) continue;
            if (!(e instanceof ParsedDocument.ImageElement) && matchSection(t) != null) continue;
            if (current.length() > 0 && SplitUtils.estimateTokens(current.toString()) + SplitUtils.estimateTokens(t) > targetTokens) {
                result.add(descriptionChunk(section, current.toString(), metadata, pageStart, pageEnd));
                current.setLength(0);
                pageStart = -1;
                pageEnd = -1;
            }
            if (current.length() > 0) current.append('\n');
            current.append(t);
            if (e.page() > 0) {
                if (pageStart < 0) pageStart = e.page();
                pageEnd = Math.max(pageEnd, e.page());
            }
        }
        if (current.length() > 0) result.add(descriptionChunk(section, current.toString(), metadata, pageStart, pageEnd));
        return result;
    }

    private Chunk descriptionChunk(Section section, String text, PatentMetadata metadata, int pageStart, int pageEnd) {
        String chunkType = switch (section.type) {
            case SEC_ABSTRACT -> "PATENT_ABSTRACT";
            case SEC_DRAWING -> "PATENT_DRAWING";
            default -> "PATENT_DESCRIPTION";
        };
        Chunk chunk = new Chunk(buildSearchHead(metadata, section.title, null, pageStart, pageEnd) + "\n" + text, chunkType);
        chunk.setChunkRole("LEAF");
        chunk.setSectionPath(section.title);
        if (pageStart > 0) chunk.setSourcePageStart(pageStart);
        if (pageEnd > 0) chunk.setSourcePageEnd(pageEnd);
        chunk.setMetadata(JSONUtil.toJsonStr(claimMetadata(metadata, section.type, section.title, null, null, null, pageStart, pageEnd)));
        return chunk;
    }

    private String buildSearchHead(PatentMetadata metadata, String sectionTitle, String claimNo, int pageStart, int pageEnd) {
        StringBuilder sb = new StringBuilder();
        if (metadata != null && StrUtil.isNotBlank(metadata.getTitle())) sb.append("[专利名称] ").append(metadata.getTitle()).append('\n');
        if (metadata != null && StrUtil.isNotBlank(metadata.getApplicationNo())) sb.append("[申请号] ").append(metadata.getApplicationNo()).append('\n');
        if (metadata != null && StrUtil.isNotBlank(metadata.getPublicationNo())) sb.append("[公布号] ").append(metadata.getPublicationNo()).append('\n');
        sb.append("[章节] ").append(sectionTitle).append('\n');
        if (StrUtil.isNotBlank(claimNo)) sb.append("[权利要求] ").append(claimNo).append('\n');
        if (pageStart > 0) {
            sb.append("[页码] ").append(pageStart);
            if (pageEnd > pageStart) sb.append('-').append(pageEnd);
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private Map<String, Object> claimMetadata(PatentMetadata metadata, String sectionType, String sectionTitle,
                                              Integer claimNo, String claimType, List<Integer> dependsOn,
                                              int pageStart, int pageEnd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domainCode", "PATENT");
        if (metadata != null) {
            m.put("applicationNo", metadata.getApplicationNo());
            m.put("publicationNo", metadata.getPublicationNo());
            m.put("title", metadata.getTitle());
            m.put("filingDate", metadata.getFilingDate());
            m.put("publicationDate", metadata.getPublicationDate());
            m.put("applicants", metadata.getApplicants());
            m.put("inventors", metadata.getInventors());
            m.put("ipcCodes", metadata.getIpcCodes());
            m.put("claimCount", metadata.getClaimCount());
            m.put("sourceType", metadata.getSourceType());
        }
        m.put("sectionType", sectionType);
        m.put("sectionTitle", sectionTitle);
        if (claimNo != null) m.put("claimNo", claimNo);
        if (claimType != null) m.put("claimType", claimType);
        if (dependsOn != null) m.put("dependsOn", dependsOn);
        if (pageStart > 0) m.put("pageStart", pageStart);
        if (pageEnd > 0) m.put("pageEnd", pageEnd);
        m.put("extractorVersion", "patent-mvp-1.3");
        return m;
    }
}
