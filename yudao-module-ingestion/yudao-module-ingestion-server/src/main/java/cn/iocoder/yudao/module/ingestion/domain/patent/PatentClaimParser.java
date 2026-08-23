package cn.iocoder.yudao.module.ingestion.domain.patent;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 权利要求解析器: 一条权利要求保持完整，并解析常见从属引用表达。
 */
public class PatentClaimParser {

    private static final Pattern CLAIM_START = Pattern.compile("^\\s*(\\d+)\\s*[.．、]", Pattern.MULTILINE);
    private static final Pattern DEPENDS_RANGE = Pattern.compile("根据权利要求\\s*(\\d+)\\s*(?:至|到|[-~～])\\s*(\\d+)(?:\\s*中任意一项)?所述");
    private static final Pattern DEPENDS_LIST = Pattern.compile("根据权利要求\\s*((?:\\d+\\s*[、,，或和及]\\s*)+\\d+)(?:\\s*中任意一项)?所述");
    private static final Pattern DEPENDS_SINGLE = Pattern.compile("根据权利要求\\s*(\\d+)\\s*(?:中任意一项)?所述");

    @Data
    public static class PatentClaim {
        private int claimNo;
        private String claimType;
        private List<Integer> dependsOn = new ArrayList<>();
        private String text;
    }

    public List<PatentClaim> parse(String claimsText) {
        List<PatentClaim> result = new ArrayList<>();
        if (StrUtil.isBlank(claimsText)) return result;
        String[] lines = claimsText.split("\\n");
        PatentClaim current = null;
        StringBuilder buffer = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher start = CLAIM_START.matcher(line);
            boolean isClaimStart = start.find();
            if (isClaimStart && current == null) {
                current = new PatentClaim();
                current.setClaimNo(Integer.parseInt(start.group(1)));
                buffer.append(line);
            } else if (isClaimStart) {
                current.setText(buffer.toString().trim());
                classify(current);
                result.add(current);
                current = new PatentClaim();
                current.setClaimNo(Integer.parseInt(start.group(1)));
                buffer.setLength(0);
                buffer.append(line);
            } else if (current != null) {
                buffer.append('\n').append(line);
            }
        }
        if (current != null && buffer.length() > 0) {
            current.setText(buffer.toString().trim());
            classify(current);
            result.add(current);
        }
        return result;
    }

    private void classify(PatentClaim claim) {
        Set<Integer> dependencies = new LinkedHashSet<>();
        Matcher range = DEPENDS_RANGE.matcher(claim.getText());
        if (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            for (int i = Math.min(from, to); i <= Math.max(from, to); i++) dependencies.add(i);
        } else {
            Matcher list = DEPENDS_LIST.matcher(claim.getText());
            if (list.find()) {
                for (String token : list.group(1).split("[、,，或和及]")) {
                    if (!token.isBlank()) dependencies.add(Integer.parseInt(token.trim()));
                }
            } else {
                Matcher single = DEPENDS_SINGLE.matcher(claim.getText());
                if (single.find()) dependencies.add(Integer.parseInt(single.group(1)));
            }
        }
        claim.setDependsOn(new ArrayList<>(dependencies));
        claim.setClaimType(dependencies.isEmpty() ? "INDEPENDENT" : "DEPENDENT");
    }
}
