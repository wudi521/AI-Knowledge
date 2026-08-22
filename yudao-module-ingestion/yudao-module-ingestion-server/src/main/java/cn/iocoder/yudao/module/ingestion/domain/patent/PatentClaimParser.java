package cn.iocoder.yudao.module.ingestion.domain.patent;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 权利要求解析器: 一条权利要求必须保持完整(不按 token 截断)。
 * 识别: 编号开头(1./1．/1、), 跨行合并, 从属依赖(根据权利要求1所述 / 1至7中任意一项)。
 */
public class PatentClaimParser {

    /** 权利要求编号开头: 数字 + . ． 、(MULTILINE: 每行开头均可匹配) */
    private static final Pattern CLAIM_START = Pattern.compile("^\\s*(\\d+)\\s*[.．、]", Pattern.MULTILINE);

    /** 从属: 根据权利要求1至7中任意一项所述 -> [1..7] */
    private static final Pattern DEPENDS_RANGE = Pattern.compile("根据权利要求\\s*(\\d+)\\s*至\\s*(\\d+)\\s*中任意一项所述");
    /** 从属: 根据权利要求1所述 -> [1] */
    private static final Pattern DEPENDS_SINGLE = Pattern.compile("根据权利要求\\s*(\\d+)\\s*(?:中任意一项)?所述");

    @Data
    public static class PatentClaim {
        private int claimNo;
        private String claimType; // INDEPENDENT / DEPENDENT
        private List<Integer> dependsOn = new ArrayList<>();
        private String text;      // 完整权利要求文本
    }

    /**
     * 解析权利要求章节文本
     *
     * @param claimsText 权利要求章节全文(多条, 可能跨行)
     * @return 权利要求列表(按编号顺序; 解析不到返回空)
     */
    public List<PatentClaim> parse(String claimsText) {
        List<PatentClaim> result = new ArrayList<>();
        if (StrUtil.isBlank(claimsText)) {
            return result;
        }
        String[] lines = claimsText.split("\n");
        PatentClaim current = null;
        StringBuilder buffer = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher start = CLAIM_START.matcher(line);
            boolean isClaimStart = start.find(); // find() 状态化: 只调用一次, 否则 else-if 会因 matcher 已推进而漏判
            if (isClaimStart && current == null) {
                // 第一条
                current = new PatentClaim();
                current.setClaimNo(Integer.parseInt(start.group(1)));
                buffer.append(line);
            } else if (isClaimStart) {
                // 新权利要求: 收尾上一条
                current.setText(buffer.toString().trim());
                classify(current);
                result.add(current);
                current = new PatentClaim();
                current.setClaimNo(Integer.parseInt(start.group(1)));
                buffer.setLength(0);
                buffer.append(line);
            } else if (current != null) {
                // 跨行合并
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

    /** 判定从属/独立并解析依赖 */
    private void classify(PatentClaim claim) {
        Matcher range = DEPENDS_RANGE.matcher(claim.getText());
        if (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                claim.getDependsOn().add(i);
            }
            claim.setClaimType("DEPENDENT");
            return;
        }
        Matcher single = DEPENDS_SINGLE.matcher(claim.getText());
        if (single.find()) {
            claim.getDependsOn().add(Integer.parseInt(single.group(1)));
            claim.setClaimType("DEPENDENT");
            return;
        }
        claim.setClaimType("INDEPENDENT");
    }
}
