package cn.iocoder.yudao.module.evidence.service.generate;

import cn.iocoder.yudao.module.evidence.domain.ClaimResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 回答生成编排器: 生成带引用回答 → 逐句断言验证 → 存在无据断言触发重试(上限 claim.max-retry, 默认 2)
 * <p>
 * 重试语义(每次"生成 + 验证"为一个尝试, 至多 maxRetry+1 个尝试):
 * <ol>
 *     <li>生成返回 null(调用异常/证据为空/回答空白) → 立即 claimFail=true, answer=null;</li>
 *     <li>验证返回 null(解析失败) → 计为一次失败尝试, 重试时重新生成(不保留该轮断言);</li>
 *     <li>全部断言 SUPPORTED → 返回 answer + claims, claimFail=false;</li>
 *     <li>存在无据断言 → 计为一次失败尝试, 追加重试反馈("上次回答有 N 句无证据支撑…请删除无据内容")
 *         后重新生成; 尝试次数 &gt; maxRetry 仍未全部支撑 → claimFail=true, answer=null,
 *         保留最后一次验证结果(claims)供诊断。</li>
 * </ol>
 * 调用上限: 2 × (maxRetry+1) 次 chat(默认 maxRetry=2 → 至多 6 次, 即 3 次生成 + 3 次验证)。
 * <p>
 * 健壮性: 任何异常 → 降级为 claimFail=true, 绝不抛出。
 */
@Slf4j
@Component
public class AnswerPipeline {

    /** 重试反馈中无据句摘要条数上限(控制提示词长度) */
    private static final int UNSUPPORTED_CLAIM_MAX = 10;

    /** maxRetry 配置缺失时的兜底(与 EvidenceProperties.Claim.maxRetry 默认一致) */
    private static final int DEFAULT_MAX_RETRY = 2;

    private final AnswerGenerator generator;
    private final ClaimVerifier verifier;
    private final EvidenceProperties properties;

    public AnswerPipeline(AnswerGenerator generator, ClaimVerifier verifier, EvidenceProperties properties) {
        this.generator = generator;
        this.verifier = verifier;
        this.properties = properties;
    }

    /**
     * 生成带引用的回答并逐句验证(含重试)
     *
     * @param query     用户问题
     * @param evidences 证据列表(去重后、按得分降序)
     * @return 验证通过: answer=最终回答, claims=全部断言, claimFail=false;
     *         失败(生成异常/验证解析失败/重试耗尽): answer=null, claimFail=true,
     *         claims=最后一次验证结果(无有效结果时为空列表)
     */
    public GenerationResult generateWithClaims(String query, List<Evidence> evidences) {
        int maxRetry = maxRetry();
        int attempts = 0;
        String feedback = null;
        List<ClaimResult> lastClaims = List.of();
        while (true) {
            // 1. 生成(首轮无反馈; 重试轮附带无据断言反馈)
            String answer = generator.generate(query, evidences, feedback);
            if (answer == null) {
                log.warn("[generateWithClaims][回答生成失败(第 {} 次尝试), claimFail=true]", attempts + 1);
                return GenerationResult.builder()
                        .answer(null)
                        .claims(List.of())
                        .claimFail(true)
                        .build();
            }
            // 2. 逐句断言验证
            List<ClaimResult> claims = verifier.verify(query, answer, evidences);
            if (claims == null) {
                // 3a. 验证解析失败: 计为一次失败尝试, 重试时要求重新生成
                attempts++;
                if (attempts > maxRetry) {
                    log.warn("[generateWithClaims][验证解析失败且重试耗尽(尝试 {} 次), claimFail=true]", attempts);
                    return GenerationResult.builder()
                            .answer(null)
                            .claims(List.of())
                            .claimFail(true)
                            .build();
                }
                feedback = "上次回答未能通过证据核查(核查结果无法解析), 请删除可能无据的内容, 只保留证据能支撑的句子, 并重新标注引用。";
                continue;
            }
            if (verifier.allSupported(claims)) {
                // 3b. 全部断言有支撑: 验证通过
                log.info("[generateWithClaims][全部断言均有证据支撑, 验证通过(尝试 {} 次)]", attempts + 1);
                return GenerationResult.builder()
                        .answer(answer)
                        .claims(claims)
                        .claimFail(false)
                        .build();
            }
            // 3c. 存在无据断言: 记录本轮结果, 触发重试
            lastClaims = claims;
            attempts++;
            if (attempts > maxRetry) {
                log.warn("[generateWithClaims][重试 {} 次后仍有无据断言, claimFail=true(无据 {} 句)]",
                        maxRetry, countUnsupported(claims));
                return GenerationResult.builder()
                        .answer(null)
                        .claims(lastClaims)
                        .claimFail(true)
                        .build();
            }
            feedback = buildFeedback(claims);
        }
    }

    /** 重试反馈: 无据句数与句子摘要(截断条数), 要求删除/改写为有据表述 */
    private String buildFeedback(List<ClaimResult> claims) {
        int count = 0;
        List<String> sentences = new ArrayList<>();
        for (ClaimResult claim : claims) {
            if (claim == null || "SUPPORTED".equals(claim.getVerdict())) {
                continue;
            }
            count++;
            if (sentences.size() < UNSUPPORTED_CLAIM_MAX && claim.getText() != null && !claim.getText().isBlank()) {
                sentences.add(claim.getText());
            }
        }
        String detail = sentences.isEmpty() ? "" : " 无据句: " + String.join(" / ", sentences);
        return "上次回答有 " + count + " 句无证据支撑。" + detail
                + " 请删除无据内容, 只保留证据能支撑的句子, 并重新标注引用。";
    }

    /** 无据断言条数 */
    private int countUnsupported(List<ClaimResult> claims) {
        int count = 0;
        for (ClaimResult claim : claims) {
            if (claim != null && !"SUPPORTED".equals(claim.getVerdict())) {
                count++;
            }
        }
        return count;
    }

    /** claim.max-retry 读取(缺省/非法回退默认 2) */
    private int maxRetry() {
        Integer value = properties.getClaim() != null ? properties.getClaim().getMaxRetry() : null;
        return value != null && value >= 0 ? value : DEFAULT_MAX_RETRY;
    }

}
