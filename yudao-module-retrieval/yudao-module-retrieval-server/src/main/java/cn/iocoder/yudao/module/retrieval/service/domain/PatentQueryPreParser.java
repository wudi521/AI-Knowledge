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
 * 申请号/公布号/权利要求号属于强结构化标识，优先用规则提取，避免交给 LLM 猜测或改写丢失。
 */
@Component
public class PatentQueryPreParser {

    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)(20\\d{10}\\.\\d)(?!\\d)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");
    private static final Pattern CLAIM_RANGE = Pattern.compile("权利要求\\s*(\\d+)\\s*(?:至|到|[-~～])\\s*(\\d+)");
    private static final Pattern CLAIM_LIST = Pattern.compile("权利要求\\s*((?:\\d+\\s*[、,，或和及]\\s*)+\\d+)");
    private static final Pattern CLAIM_SINGLE = Pattern.compile("权利要求\\s*(\\d+)");

    public PatentQueryHints parse(String query) {
        PatentQueryHints.PatentQueryHintsBuilder builder = PatentQueryHints.builder();
        if (query == null || query.isBlank()) {
            return builder.claimNos(List.of()).build();
        }

        Matcher app = APPLICATION_NO.matcher(query);
        if (app.find()) {
            builder.applicationNo(app.group(1));
        }

        Matcher pub = PUBLICATION_NO.matcher(query);
        if (pub.find()) {
            builder.publicationNo(normalizePublicationNo(pub.group()));
        }

        List<Integer> claimNos = parseClaimNos(query);
        builder.claimNos(claimNos);
        if (claimNos.size() == 1) {
            builder.claimNo(claimNos.get(0));
        }

        builder.claimDependencyIntent(containsAny(query, "引用", "依赖", "从属", "在先权利要求", "引用了哪些", "根据权利要求"));
        builder.bibliographicIntent(containsAny(query, "申请号", "公布号", "申请人", "发明人", "发明名称", "专利名称", "IPC", "Int.Cl"));
        builder.claimIntent(query.contains("权利要求"));
        return builder.build();
    }

    private List<Integer> parseClaimNos(String query) {
        Matcher range = CLAIM_RANGE.matcher(query);
        if (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            List<Integer> result = new ArrayList<>();
            for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                result.add(i);
            }
            return result;
        }

        Matcher list = CLAIM_LIST.matcher(query);
        if (list.find()) {
            List<Integer> result = new ArrayList<>();
            for (String token : list.group(1).split("[、,，或和及]")) {
                if (!token.isBlank()) {
                    result.add(Integer.parseInt(token.trim()));
                }
            }
            return result.stream().distinct().toList();
        }

        Matcher single = CLAIM_SINGLE.matcher(query);
        if (single.find()) {
            return List.of(Integer.parseInt(single.group(1)));
        }
        return List.of();
    }

    private String normalizePublicationNo(String raw) {
        return raw.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
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
        private boolean claimDependencyIntent;
        private boolean bibliographicIntent;
        private boolean claimIntent;

        public boolean hasExactDocumentIdentifier() {
            return applicationNo != null || publicationNo != null;
        }

        public boolean hasExactClaim() {
            return hasExactDocumentIdentifier() && claimNo != null;
        }
    }
}
