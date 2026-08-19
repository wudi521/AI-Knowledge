package cn.iocoder.yudao.module.evidence.service.slot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 槽位检测结果(领域对象)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlotDetectionResult {

    /** 问题是否属于槽位集对应领域(如"你好" → false, 放行) */
    private boolean applicable;

    /** 抽取的槽位值: slotCode -> 抽取原文(可能为空串) */
    private Map<String, String> extracted;

    /** 缺失的必填槽位(按 sort 升序) */
    private List<MissingSlot> missing;

    /**
     * 缺失槽位(含编码/名, 供组反问句)
     */
    @Data
    @AllArgsConstructor
    public static class MissingSlot {

        /** 槽位编码 */
        private String code;

        /** 槽位名(如 故障性质) */
        private String name;

    }

}
