package cn.iocoder.yudao.module.chat.enums.feedback;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * AI 反馈类型枚举
 * <p>
 * 与 ai_feedback.type 列一致(varchar(16)): THUMB_UP 点赞 / THUMB_DOWN 点踩(点踩触发考题生成闭环)。
 */
@Getter
@AllArgsConstructor
public enum FeedbackTypeEnum {

    THUMB_UP("THUMB_UP", "点赞"),
    THUMB_DOWN("THUMB_DOWN", "点踩(差评, 自动生成评测用例)");

    private final String type;
    private final String desc;

    public static boolean isValid(String type) {
        return Arrays.stream(values()).anyMatch(e -> e.getType().equals(type));
    }

}
