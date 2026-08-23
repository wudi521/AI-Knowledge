package cn.iocoder.yudao.module.chat.enums.feedback;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 点踩原因(NOT_HELPFUL 必选, 供 Failure Classification / Bad Case 统计)
 */
public enum FeedbackReasonEnum {

    WRONG_ANSWER("WRONG_ANSWER", "回答错误"),
    NOT_ANSWERED("NOT_ANSWERED", "没有回答到问题"),
    WRONG_EVIDENCE("WRONG_EVIDENCE", "引用证据不对"),
    INCOMPLETE("INCOMPLETE", "信息不完整"),
    OUTDATED_KNOWLEDGE("OUTDATED_KNOWLEDGE", "知识已过期"),
    TOO_VERBOSE("TOO_VERBOSE", "回答太啰嗦"),
    TOO_SLOW("TOO_SLOW", "回答太慢"),
    OTHER("OTHER", "其他");

    private final String code;
    private final String label;

    private static final Set<String> VALID = Stream.of(values())
            .map(FeedbackReasonEnum::getCode)
            .collect(Collectors.toSet());

    FeedbackReasonEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static boolean isValid(String code) {
        return code != null && VALID.contains(code);
    }

}
