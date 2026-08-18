package cn.iocoder.yudao.module.chat.enums.conversation;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 会话状态枚举(ai_conversation.status)
 * <p>
 * 状态机(不允许跳跃):
 * <pre>
 *   ACTIVE --转人工--> TRANSFERRED --接单--> CLOSED
 *      \_________________直接关闭_________________/
 * </pre>
 * 即: ACTIVE→TRANSFERRED、ACTIVE→CLOSED、TRANSFERRED→CLOSED; CLOSED 为终态, ACTIVE 不可回退。
 */
@Getter
@AllArgsConstructor
public enum ConversationStatusEnum {

    /** 进行中(机器人会话) */
    ACTIVE("ACTIVE", "进行中"),
    /** 待人工接单(已申请转人工) */
    TRANSFERRED("TRANSFERRED", "待人工接单"),
    /** 已关闭(人工接单完成 / 会话结束) */
    CLOSED("CLOSED", "已关闭");

    private final String status;
    private final String name;

    public static ConversationStatusEnum getByStatus(String status) {
        if (status == null) {
            return null;
        }
        for (ConversationStatusEnum item : values()) {
            if (item.status.equals(status)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 状态机: 目标状态 → 允许的当前(来源)状态集合
     * <p>
     * 用于 SQL 原子守卫(WHERE status IN (...)), 避免先读后写的 TOCTOU 竞态:
     * 并发下仅来源状态仍匹配的那一次更新能生效。
     */
    public List<String> getAllowedFromStatuses() {
        return switch (this) {
            case TRANSFERRED -> List.of(ACTIVE.getStatus());
            case CLOSED -> List.of(ACTIVE.getStatus(), TRANSFERRED.getStatus());
            default -> Collections.emptyList();
        };
    }

}
