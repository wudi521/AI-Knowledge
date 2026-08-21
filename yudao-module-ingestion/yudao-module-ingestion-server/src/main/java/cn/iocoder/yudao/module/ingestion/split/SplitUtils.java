package cn.iocoder.yudao.module.ingestion.split;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分公共工具(估算 token / 句子级切分 / 重叠 / 标题链)
 */
public final class SplitUtils {

    private SplitUtils() {
    }

    /** 粗略估计 token 数: 统一按 1.5 字符/token(中文为主场景) */
    public static int estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 1.5);
    }

    /** 长段按句子切块(每块 ≤ maxTokens; 句子完整不截断) */
    public static List<String> splitBySentences(String para, int maxTokens) {
        List<String> sentences = new ArrayList<>();
        for (String s : para.split("(?<=[。！？.!?])")) {
            if (s != null && !s.isBlank()) {
                sentences.add(s.trim());
            }
        }
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (estimateTokens(current.toString()) + estimateTokens(sentence) > maxTokens
                    && current.length() > 0) {
                blocks.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            blocks.add(current.toString().trim());
        }
        return blocks;
    }

    /** 相邻块重叠: 每块(除首块)开头拼上一块末尾 overlap 句, 缓解边界切分丢关键句 */
    public static List<String> applyOverlap(List<String> blocks, int overlap) {
        if (overlap <= 0 || blocks.size() <= 1) {
            return blocks;
        }
        List<String> result = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            String prefix = i == 0 ? "" : lastSentences(blocks.get(i - 1), overlap);
            result.add(prefix.isEmpty() ? blocks.get(i) : prefix + blocks.get(i));
        }
        return result;
    }

    /** 取文本末尾 n 个句子(按句号等边界切; n 不足返回整段) */
    public static String lastSentences(String text, int n) {
        if (n <= 0 || text == null || text.isBlank()) {
            return "";
        }
        String[] parts = text.split("(?<=[。！？.!?])");
        if (parts.length <= n) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - n; i < parts.length; i++) {
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** 标题链前缀: ["3.2 权利要求书", "3.2.1 装置"] → "[3.2 权利要求书 > 3.2.1 装置] " */
    public static String titleChainPrefix(java.util.List<String> chain) {
        if (chain == null || chain.isEmpty()) {
            return "";
        }
        return "[" + String.join(" > ", chain) + "] ";
    }
}
