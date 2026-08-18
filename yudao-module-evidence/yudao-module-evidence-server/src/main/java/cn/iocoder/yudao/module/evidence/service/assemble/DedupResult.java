package cn.iocoder.yudao.module.evidence.service.assemble;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 去重结果(证据去重器的输出)
 */
@Data
@AllArgsConstructor
public class DedupResult {

    /** 去重后的证据(保持原相对顺序: 按得分降序) */
    private List<Evidence> deduped;

    /** 被合并移除的条数 */
    private int removedCount;

}
