package cn.iocoder.yudao.module.evidence.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 回答生成结果(生成 + 逐句断言验证后的最终产物)
 * <p>
 * 约定: claimFail=true 时 answer 恒为 null(生成失败 / 重试耗尽仍有无据断言),
 * claims 保留最后一次验证结果(供诊断); 正常路径 claimFail=false, answer 为全部断言均通过验证的回答。
 * verificationDegraded=true 表示验证器解析故障重试耗尽后降级信任生成(回答未经过完整逐句验证)。
 */
@Data
@Builder
public class GenerationResult {

    /** 最终回答(全部断言均被证据支撑; claimFail=true 时为 null) */
    private String answer;

    /** 逐句断言验证结果(claims) */
    private List<ClaimResult> claims;

    /** 是否验证失败(重试耗尽/生成失败 → true; 此时 answer=null) */
    private boolean claimFail;

    /** 是否降级信任生成(验证器解析故障重试耗尽; 回答未完整验证, 仅供诊断) */
    private boolean verificationDegraded;

}
