package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史对话格式化工具(纯静态工具类, 回答生成器/断言验证器共享同一格式)
 * <p>
 * 职责: 将 evidence-api 的 {@link ChatTurnDTO} 列表渲染为提示词中的历史对话块,
 * 供 LLM 理解指代(那/它/多少钱), 不参与证据支撑判定。
 * <p>
 * 格式约定:
 * <pre>
 * 历史对话(时间从早到晚, 仅供指代理解, 不要复述):
 * [用户] X100 Pro 碎屏能免费修吗
 * [客服] 不能, 屏幕碎裂属意外损坏。
 * </pre>
 * role 映射: USER → 用户, SYSTEM → 整轮过滤, 其余(AI 等) → 客服。
 * <p>
 * 截断保护(防超长提示词): 单轮内容先截断至 perTurnMaxLen(默认 200 字),
 * 再按 totalMaxLen(默认 2000 字)从最旧轮次开始丢弃, 保留最近轮次。
 * 任何入参(null/空/非法上限)均不抛异常, null/空 → 返回空串。
 */
public final class ContextFormatter {

    /** 单轮内容截断长度(字)默认值 */
    public static final int DEFAULT_PER_TURN_MAX_LEN = 200;

    /** 历史对话块总长度上限(字)默认值 */
    public static final int DEFAULT_TOTAL_MAX_LEN = 2000;

    private ContextFormatter() {
    }

    /**
     * 格式化历史对话(默认截断参数: 单轮 200 字, 总计 2000 字)
     *
     * @param history 对话轮次(时间从早到晚; 可为 null/空)
     * @return 历史对话块文本(无历史/全部被过滤 → 空串; 绝不抛异常)
     */
    public static String formatHistory(List<ChatTurnDTO> history) {
        return formatHistory(history, DEFAULT_PER_TURN_MAX_LEN, DEFAULT_TOTAL_MAX_LEN);
    }

    /**
     * 格式化历史对话
     *
     * @param history       对话轮次(时间从早到晚; 可为 null/空)
     * @param perTurnMaxLen 单轮内容截断长度(字; 非法上限按不截断处理)
     * @param totalMaxLen   历史块总长度上限(字; 超限从最旧轮次开始丢弃; 非法上限 → 空串)
     * @return 历史对话块文本(无历史/全部被过滤 → 空串; 绝不抛异常)
     */
    public static String formatHistory(List<ChatTurnDTO> history, int perTurnMaxLen, int totalMaxLen) {
        if (history == null || history.isEmpty() || totalMaxLen <= 0) {
            return "";
        }
        String header = "历史对话(时间从早到晚, 仅供指代理解, 不要复述):";
        // 1. 逐轮渲染(过滤 SYSTEM/空内容, 单轮截断); 先全部渲染再按总长丢最旧轮次
        List<String> lines = new ArrayList<>();
        for (ChatTurnDTO turn : history) {
            if (turn == null || StrUtil.isBlank(turn.getContent())) {
                continue;
            }
            String role = resolveRole(turn.getRole());
            if (role == null) {
                continue; // SYSTEM 整轮过滤
            }
            lines.add("[" + role + "] " + truncate(StrUtil.trim(turn.getContent()), perTurnMaxLen));
        }
        if (lines.isEmpty()) {
            return "";
        }
        // 2. 总长截断: 头部固定开销 + 从最新轮次(列表尾部)往前累计, 放不下即丢弃该轮及更早轮次
        int headerOverhead = header.length() + 1; // 头部 + 首个换行
        if (headerOverhead > totalMaxLen) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        int used = headerOverhead;
        for (int i = lines.size() - 1; i >= 0; i--) {
            int add = lines.get(i).length() + 1; // 行内容 + 换行
            if (used + add > totalMaxLen) {
                break;
            }
            kept.add(0, lines.get(i));
            used += add;
        }
        if (kept.isEmpty()) {
            return "";
        }
        return header + "\n" + String.join("\n", kept);
    }

    /** role 映射: USER → 用户, SYSTEM → null(过滤), 其余(AI 等) → 客服 */
    private static String resolveRole(String role) {
        if (role == null) {
            return "客服";
        }
        String upper = role.trim().toUpperCase(java.util.Locale.ROOT);
        if ("USER".equals(upper)) {
            return "用户";
        }
        if ("SYSTEM".equals(upper)) {
            return null;
        }
        return "客服";
    }

    /** 截断至 maxLen 字(超出加省略号; maxLen ≤ 0 视为不截断) */
    private static String truncate(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        if (maxLen <= 0 || content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen) + "…";
    }

}
