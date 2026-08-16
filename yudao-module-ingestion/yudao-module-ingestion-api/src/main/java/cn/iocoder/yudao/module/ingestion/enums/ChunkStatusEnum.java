package cn.iocoder.yudao.module.ingestion.enums;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * AI 知识片段状态枚举
 */
@Getter
@AllArgsConstructor
public enum ChunkStatusEnum implements ArrayValuable<String> {

    PUBLISHED("PUBLISHED", "已发布"),
    REVIEW("REVIEW", "待审核"),
    DISABLED("DISABLED", "已禁用");

    public static final String[] ARRAYS = Arrays.stream(values())
            .map(ChunkStatusEnum::getStatus).toArray(String[]::new);

    /** 状态值 */
    private final String status;
    /** 状态名 */
    private final String name;

    @Override
    public String[] array() {
        return ARRAYS;
    }

    /**
     * 根据状态值获取枚举
     *
     * @param status 状态值
     * @return 枚举, 未知返回 null
     */
    public static ChunkStatusEnum fromStatus(String status) {
        return ArrayUtil.firstMatch(item -> item.getStatus().equals(status), values());
    }

}
