package cn.iocoder.yudao.module.chat.service.context.model;

import lombok.Data;

import java.util.List;

/**
 * 结果集引用重校验结果(CQ-38): 多轮引用前校验 tenant/kb/domain 一致 + 文档 ACL 可见 + 发布版本有效。
 * <p>
 * valid=true 全部有效; contextChanged=true 表示有失效实体被剔除(语义已变); invalid 时 reasonCode 指明原因
 * (STALE_RESULT_SET / PERMISSION_CHANGED / DOMAIN_MISMATCH / EMPTY_RESULT_SET)。
 */
@Data
public class RevalidationResult {

    /** 结果集是否整体有效(无任何失效) */
    private boolean valid;

    /** 上下文是否已变化(部分失效被剔除; true 时引用方需用 remainingIds 而非原始集合) */
    private boolean contextChanged;

    /** 失效原因码(valid=false 时): STALE_RESULT_SET/PERMISSION_CHANGED/DOMAIN_MISMATCH/EMPTY_RESULT_SET */
    private String reasonCode;

    /** 剔除后剩余有效实体 id(保序; contextChanged 时使用) */
    private List<Long> remainingIds;

    /** 被剔除的失效实体 id */
    private List<Long> removedIds;

    public static RevalidationResult valid() {
        RevalidationResult r = new RevalidationResult();
        r.setValid(true);
        r.setContextChanged(false);
        return r;
    }

    public static RevalidationResult invalid(String reasonCode) {
        RevalidationResult r = new RevalidationResult();
        r.setValid(false);
        r.setContextChanged(true);
        r.setReasonCode(reasonCode);
        return r;
    }

    public static RevalidationResult partial(List<Long> remainingIds, List<Long> removedIds) {
        RevalidationResult r = new RevalidationResult();
        r.setValid(false);
        r.setContextChanged(true);
        r.setRemainingIds(remainingIds);
        r.setRemovedIds(removedIds);
        return r;
    }
}
