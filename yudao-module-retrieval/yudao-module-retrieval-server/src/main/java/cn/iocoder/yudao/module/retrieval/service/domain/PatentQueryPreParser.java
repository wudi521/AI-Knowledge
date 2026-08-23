package cn.iocoder.yudao.module.retrieval.service.domain;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 专利查询确定性预解析器。
 * <p>
 * 申请号/公布号/权利要求号以及明确的著录字段查询属于强结构化信息，优先用规则提取，
 * 避免交给 LLM 猜测、改写丢失，或把“申请号 X 的技术方案”误判成著录信息查询。
 */
@Component
public class PatentQueryPreParser {

    public static final String META_CLAIM_COUNT = "CLAIM_COUNT";
    public static final String META_TITLE = "TITLE";
    public static final String META_APPLICANTS = "APPLICANTS";
    public static final String META_INVENTORS = "INVENTORS";
    public static final String META_IPC_CODES = "IPC_CODES";
    public static final String META_FILING_DATE = "FILING_DATE";
    public static final String META_PUBLICATION_DATE = "PUBLICATION_DATE";
    public static final String META_APPLICATION_NO = "APPLICATION_NO";
    public static final String META_PUBLICATION_NO = "PUBLICATION_NO";
    public static final String META_SOURCE_TYPE = "SOURCE_TYPE";

    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)(20\\d{10}\\.\\d)(?!\\d)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");
    private static final Pattern CLAIM_RANGE = Pattern.compile("权利要求\\s*(\\d+)\\s*(?:至|到|[-~～])\\s*(\\d+)");
    private static final Pattern CLAIM_LIST = Pattern.compile("权利要求\\s*((?:\\d+\\s*[、,，或和及]\\s*)+\\d+)");
    private static final Pattern CLAIM_SINGLE = Pattern.compile("权利要求\\s*(\\d+)");

    public PatentQueryHints parse(String query) {
        PatentQueryHints.PatentQueryHintsBuilder builder = PatentQueryHints.builder();
        if (query == null || query.isBlank()) {
            return builder.claimNos(List.of()).metadataFields(List.of()).build();
        }

        String applicationNo = null;
        Matcher app = APPLICATION_NO.matcher(query);
        if (app.find()) {
            applicationNo = app.group(1);
            builder.applicationNo(applicationNo);
        }

        String publicationNo = null;
        Matcher pub = PUBLICATION_NO.matcher(query);
        if (pub.find()) {
            publicationNo = normalizePublicationNo(pub.group());
            builder.publicationNo(publicationNo);
        }

        List<Integer> claimNos = parseClaimNos(query);
        builder.claimNos(claimNos);
        if (claimNos.size() == 1) {
            builder.claimNo(claimNos.get(0));
        }

        boolean claimCountIntent = isClaimCountQuestion(query);
        List<String> metadataFields = parseMetadataFields(query, applicationNo, publicationNo, claimCountIntent);

        builder.metadataFields(metadataFields);
        builder.claimCountIntent(claimCountIntent);
        builder.claimDependencyIntent(!claimCountIntent
                && containsAny(query, "引用", "依赖", "从属", "在先权利要求", "引用了哪些", "根据权利要求"));
        builder.bibliographicIntent(!metadataFields.isEmpty());
        builder.claimIntent(!claimCountIntent && query.contains("权利要求"));
        return builder.build();
    }

    private List<String> parseMetadataFields(String query, String applicationNo, String publicationNo,
                                             boolean claimCountIntent) {
        List<String> fields = new ArrayList<>();
        if (claimCountIntent) addField(fields, META_CLAIM_COUNT);
        if (containsAny(query, "发明名称", "专利名称")) addField(fields, META_TITLE);
        if (query.contains("申请人")) addField(fields, META_APPLICANTS);
        if (query.contains("发明人")) addField(fields, META_INVENTORS);
        if (containsAnyIgnoreCase(query, "IPC", "INT.CL") || containsAny(query, "分类号", "国际专利分类")) {
            addField(fields, META_IPC_CODES);
        }
        if (containsAny(query, "申请日", "申请日期")) addField(fields, META_FILING_DATE);
        if (containsAny(query, "申请公布日", "公布日", "公开日", "公开日期")) addField(fields, META_PUBLICATION_DATE);
        if (containsAny(query, "文献类型", "申请类型", "专利类型")) addField(fields, META_SOURCE_TYPE);

        // “申请号 2023... 的技术方案”里的“申请号”只是定位条件，不是目标字段。
        // 只有当前问题没有直接给出该编号时，才把“申请号/公布号”视为要查询的著录字段。
        if (applicationNo == null && query.contains("申请号")) addField(fields, META_APPLICATION_NO);
        if (publicationNo == null && containsAny(query, "公布号", "申请公布号", "公开号")) {
            addField(fields, META_PUBLICATION_NO);
        }
        return fields;
    }

    private boolean isClaimCountQuestion(String query) {
        return containsAny(query,
                "几项权利要求", "多少项权利要求", "权利要求数量", "权利要求数", "权利要求总数",
                "共有几项", "一共有几项", "总共有几项", "共几项权利要求", "共计几项权利要求");
    }

    private void addField(List<String> fields, String field) {
        if (!fields.contains(field)) fields.add(field);
    }

    private List<Integer> parseClaimNos(String query) {
        Matcher range = CLAIM_RANGE.matcher(query);
        if (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            List<Integer> result = new ArrayList<>();
            for (int i = Math.min(from, to); i <= Math.max(from, to); i++) result.add(i);
            return result;
        }

        Matcher list = CLAIM_LIST.matcher(query);
        if (list.find()) {
            List<Integer> result = new ArrayList<>();
            for (String token : list.group(1).split("[、,，或和及]")) {
                if (!token.isBlank()) result.add(Integer.parseInt(token.trim()));
            }
            return result.stream().distinct().toList();
        }

        Matcher single = CLAIM_SINGLE.matcher(query);
        if (single.find()) return List.of(Integer.parseInt(single.group(1)));
        return List.of();
    }

    private String normalizePublicationNo(String raw) {
        return raw.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    private boolean containsAnyIgnoreCase(String text, String... keywords) {
        String upper = text.toUpperCase();
        for (String keyword : keywords) if (upper.contains(keyword.toUpperCase())) return true;
        return false;
    }

    @Data
    @Builder
    public static class PatentQueryHints {
        private String applicationNo;
        private String publicationNo;
        private Integer claimNo;
        @Builder.Default
        private List<Integer> claimNos = List.of();
        @Builder.Default
        private List<String> metadataFields = List.of();
        private boolean claimCountIntent;
        private boolean claimDependencyIntent;
        private boolean bibliographicIntent;
        private boolean claimIntent;

        public boolean hasExactDocumentIdentifier() {
            return applicationNo != null || publicationNo != null;
        }

        public boolean hasExactClaim() {
            return hasExactDocumentIdentifier() && claimNo != null;
        }

        public boolean hasDeterministicExactMetadata() {
            return hasExactDocumentIdentifier() && metadataFields != null && !metadataFields.isEmpty();
        }
    }
}
