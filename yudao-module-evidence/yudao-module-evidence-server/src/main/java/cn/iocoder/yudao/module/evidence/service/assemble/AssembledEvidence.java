package cn.iocoder.yudao.module.evidence.service.assemble;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 组装结果(证据组装器的输出, 供冲突判定/充分性判定/Claim 验证等后续环节使用)
 */
@Data
@AllArgsConstructor
public class AssembledEvidence {

    /** 组装后的证据(按得分降序) */
    private List<Evidence> evidences;

    /** 问题涉及的产品/品牌(透传检索分析结果, 供充分性判定使用) */
    private List<String> questionProducts;

    /** 产品/品牌一致性门禁: true = 拒绝作答(透传检索结果) */
    private Boolean answerBlocked;

    /** 拒绝作答原因 */
    private String answerReason;

    /**
     * 空结果(检索 RPC 失败/异常时优雅降级返回, 不抛异常)
     */
    public static AssembledEvidence empty() {
        return new AssembledEvidence(Collections.emptyList(), Collections.emptyList(), null, null);
    }

}
