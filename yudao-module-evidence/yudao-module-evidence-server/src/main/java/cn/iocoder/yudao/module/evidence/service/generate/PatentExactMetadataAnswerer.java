package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 专利强结构著录信息确定性回答器。
 * <p>
 * 适用于“公布号 X 有几项权利要求 / 申请人 / 发明人 / IPC / 日期”等明确文档 + 明确字段查询。
 * 所需字段已在 ingestion 阶段写入每个 Patent chunk metadata，因此无需再让 LLM 枚举、归纳和二次验证。
 */
final class PatentExactMetadataAnswerer {

    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)20\\d{10}\\.\\d(?!\\d)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");

    private PatentExactMetadataAnswerer() {
    }

    static DirectAnswer tryAnswer(String query, List<Evidence> evidences) {
        if (StrUtil.isBlank(query) || evidences == null || evidences.isEmpty()) return null;
        boolean hasApplicationIdentifier = APPLICATION_NO.matcher(query).find();
        boolean hasPublicationIdentifier = PUBLICATION_NO.matcher(query).find();
        if (!hasApplicationIdentifier && !hasPublicationIdentifier) return null;

        List<Field> fields = requestedFields(query, hasApplicationIdentifier, hasPublicationIdentifier);
        if (fields.isEmpty()) return null;

        for (int i = 0; i < evidences.size(); i++) {
            Evidence evidence = evidences.get(i);
            JSONObject meta = patentMetadata(evidence);
            if (meta == null || !hasAll(meta, fields)) continue;
            String answer = render(meta, fields, i + 1);
            if (StrUtil.isNotBlank(answer)) return new DirectAnswer(answer, i);
        }
        return null;
    }

    private static JSONObject patentMetadata(Evidence evidence) {
        if (evidence == null || StrUtil.isBlank(evidence.getChunkMetadata())) return null;
        try {
            JSONObject meta = JSONUtil.parseObj(evidence.getChunkMetadata());
            return "PATENT".equalsIgnoreCase(meta.getStr("domainCode")) ? meta : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static List<Field> requestedFields(String query, boolean hasApplicationIdentifier,
                                               boolean hasPublicationIdentifier) {
        List<Field> fields = new ArrayList<>();
        if (containsAny(query, "几项权利要求", "多少项权利要求", "权利要求数量", "权利要求数", "权利要求总数",
                "共有几项", "一共有几项", "总共有几项", "共几项权利要求", "共计几项权利要求")) {
            fields.add(Field.CLAIM_COUNT);
        }
        if (containsAny(query, "发明名称", "专利名称")) fields.add(Field.TITLE);
        if (query.contains("申请人")) fields.add(Field.APPLICANTS);
        if (query.contains("发明人")) fields.add(Field.INVENTORS);
        String upper = query.toUpperCase();
        if (upper.contains("IPC") || upper.contains("INT.CL") || containsAny(query, "分类号", "国际专利分类")) {
            fields.add(Field.IPC_CODES);
        }
        if (containsAny(query, "申请日", "申请日期")) fields.add(Field.FILING_DATE);
        if (containsAny(query, "申请公布日", "公布日", "公开日", "公开日期")) fields.add(Field.PUBLICATION_DATE);
        if (containsAny(query, "文献类型", "申请类型", "专利类型")) fields.add(Field.SOURCE_TYPE);

        // 编号已经出现在问题里时，它只是定位条件；只有未提供该编号但明确询问它时才作为目标字段。
        if (!hasApplicationIdentifier && query.contains("申请号")) fields.add(Field.APPLICATION_NO);
        if (!hasPublicationIdentifier && containsAny(query, "公布号", "申请公布号", "公开号")) {
            fields.add(Field.PUBLICATION_NO);
        }
        return fields.stream().distinct().toList();
    }

    private static boolean hasAll(JSONObject meta, List<Field> fields) {
        for (Field field : fields) {
            switch (field) {
                case CLAIM_COUNT -> {
                    Integer value = meta.getInt("claimCount");
                    if (value == null || value <= 0) return false;
                }
                case TITLE -> { if (StrUtil.isBlank(meta.getStr("title"))) return false; }
                case APPLICANTS -> { if (emptyArray(meta, "applicants")) return false; }
                case INVENTORS -> { if (emptyArray(meta, "inventors")) return false; }
                case IPC_CODES -> { if (emptyArray(meta, "ipcCodes")) return false; }
                case FILING_DATE -> { if (StrUtil.isBlank(meta.getStr("filingDate"))) return false; }
                case PUBLICATION_DATE -> { if (StrUtil.isBlank(meta.getStr("publicationDate"))) return false; }
                case APPLICATION_NO -> { if (StrUtil.isBlank(meta.getStr("applicationNo"))) return false; }
                case PUBLICATION_NO -> { if (StrUtil.isBlank(meta.getStr("publicationNo"))) return false; }
                case SOURCE_TYPE -> { if (StrUtil.isBlank(meta.getStr("sourceType"))) return false; }
            }
        }
        return true;
    }

    private static boolean emptyArray(JSONObject meta, String key) {
        JSONArray arr = meta.getJSONArray(key);
        return arr == null || arr.isEmpty();
    }

    private static String render(JSONObject meta, List<Field> fields, int citationNo) {
        String citation = " [C" + citationNo + "]";
        List<String> lines = new ArrayList<>();
        String publicationNo = meta.getStr("publicationNo");
        String applicationNo = meta.getStr("applicationNo");
        String subject = StrUtil.isNotBlank(publicationNo) ? publicationNo
                : (StrUtil.isNotBlank(applicationNo) ? "申请号 " + applicationNo : "该专利");

        for (Field field : fields) {
            switch (field) {
                case CLAIM_COUNT -> lines.add(subject + " 共有 " + meta.getInt("claimCount") + " 项权利要求。" + citation);
                case TITLE -> lines.add("发明名称：" + meta.getStr("title") + "。" + citation);
                case APPLICANTS -> lines.add("申请人：" + join(meta.getJSONArray("applicants")) + "。" + citation);
                case INVENTORS -> lines.add("发明人：" + join(meta.getJSONArray("inventors")) + "。" + citation);
                case IPC_CODES -> lines.add("IPC：" + join(meta.getJSONArray("ipcCodes")) + "。" + citation);
                case FILING_DATE -> lines.add("申请日：" + meta.getStr("filingDate") + "。" + citation);
                case PUBLICATION_DATE -> lines.add("申请公布日：" + meta.getStr("publicationDate") + "。" + citation);
                case APPLICATION_NO -> lines.add("申请号：" + meta.getStr("applicationNo") + "。" + citation);
                case PUBLICATION_NO -> lines.add("公布号：" + meta.getStr("publicationNo") + "。" + citation);
                case SOURCE_TYPE -> lines.add("文献类型：" + meta.getStr("sourceType") + "。" + citation);
            }
        }
        return String.join("\n", lines);
    }

    private static String join(JSONArray arr) {
        List<String> values = new ArrayList<>();
        if (arr != null) {
            for (Object value : arr) {
                if (value != null && StrUtil.isNotBlank(value.toString())) values.add(value.toString());
            }
        }
        return String.join("、", values);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    private enum Field {
        CLAIM_COUNT, TITLE, APPLICANTS, INVENTORS, IPC_CODES,
        FILING_DATE, PUBLICATION_DATE, APPLICATION_NO, PUBLICATION_NO, SOURCE_TYPE
    }

    record DirectAnswer(String answer, int evidenceIndex) {
    }
}
