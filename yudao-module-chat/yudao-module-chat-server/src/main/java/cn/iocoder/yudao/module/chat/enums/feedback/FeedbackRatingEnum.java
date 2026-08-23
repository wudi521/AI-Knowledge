package cn.iocoder.yudao.module.chat.enums.feedback;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 回答反馈评价(P0-11: 有用/无用)
 * <p>
 * 只记录用户主观评价, 禁止直接作为模型训练标签(用户可能误点/无法判断事实真伪), 先作为质量信号。
 */
public enum FeedbackRatingEnum {

    HELPFUL("HELPFUL", "有用"),
    NOT_HELPFUL("NOT_HELPFUL", "无用");

    private final String rating;
    private final String label;

    private static final Set<String> VALID = Stream.of(values())
            .map(FeedbackRatingEnum::getRating)
            .collect(Collectors.toSet());

    FeedbackRatingEnum(String rating, String label) {
        this.rating = rating;
        this.label = label;
    }

    public String getRating() {
        return rating;
    }

    public String getLabel() {
        return label;
    }

    public static boolean isValid(String rating) {
        return rating != null && VALID.contains(rating);
    }

}
