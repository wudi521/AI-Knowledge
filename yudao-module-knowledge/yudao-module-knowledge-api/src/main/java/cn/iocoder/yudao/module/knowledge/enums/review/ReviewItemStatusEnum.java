package cn.iocoder.yudao.module.knowledge.enums.review;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核条目状态
 */
@Getter
@AllArgsConstructor
public enum ReviewItemStatusEnum {

    PENDING("PENDING", "待审核"),
    APPROVED("APPROVED", "已通过"),
    REJECTED("REJECTED", "已驳回");

    private final String status;
    private final String name;

}
