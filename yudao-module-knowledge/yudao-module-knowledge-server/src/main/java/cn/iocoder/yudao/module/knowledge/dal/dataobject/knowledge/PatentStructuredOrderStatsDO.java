package cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge;

import lombok.Data;

/** PATENT typed order pushdown 的完整性统计。 */
@Data
public class PatentStructuredOrderStatsDO {
    private Long sourceEntityCount;
    private Long missingValueCount;
    private Long conflictCount;
}
