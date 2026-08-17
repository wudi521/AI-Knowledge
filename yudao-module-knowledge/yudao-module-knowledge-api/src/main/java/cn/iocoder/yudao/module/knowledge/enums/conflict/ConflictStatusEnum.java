package cn.iocoder.yudao.module.knowledge.enums.conflict;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 版本冲突状态(对应 ai_conflict.status, BR-008)
 */
@Getter
@AllArgsConstructor
public enum ConflictStatusEnum implements ArrayValuable<String> {

    PENDING("PENDING", "待裁决"),
    RESOLVED_NEW("RESOLVED_NEW", "已裁决·以新版为准"),
    RESOLVED_OLD("RESOLVED_OLD", "已裁决·以旧版为准");

    public static final String[] ARRAYS = Arrays.stream(values())
            .map(ConflictStatusEnum::getStatus).toArray(String[]::new);

    /** 状态值 */
    private final String status;
    /** 状态名 */
    private final String name;

    @Override
    public String[] array() {
        return ARRAYS;
    }

    /** 根据状态值获取枚举, 未知返回 null */
    public static ConflictStatusEnum fromStatus(String status) {
        return ArrayUtil.firstMatch(item -> item.getStatus().equals(status), values());
    }

}
