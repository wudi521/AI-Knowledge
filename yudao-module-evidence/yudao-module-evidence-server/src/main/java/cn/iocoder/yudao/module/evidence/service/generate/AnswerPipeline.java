package cn.iocoder.yudao.module.evidence.service.generate;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.ClaimResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 回答生成编排器: 确定性快路径 → 生成带引用回答 → 逐句断言验证 → 无据断言重试。
 */
@Slf4j
@Component
public class AnswerPipeline {

    private static final int UNSUPPORTED_CLAIM_MAX = 10;
    private static final int DEFAULT_MAX_RETRY = 2;

    private final AnswerGenerator generator;
    private final ClaimVerifier verifier;
    private final EvidenceProperties properties;

    public AnswerPipeline(AnswerGenerator generator, ClaimVerifier verifier, EvidenceProperties properties) {
        this.generator = generator;
        this.verifier = verifier;
        this.properties = properties;
    }

    public GenerationResult generateWithClaims(String query, List<Evidence> evidences) {
        return generateWithClaims(query, evidences, null);
    }

    public GenerationResult generateWithClaims(String query, List<Evidence> evidences, List<ChatTurnDTO> history) {
        // PATENT EXACT_METADATA：字段来自 ingestion 已验证的结构化 metadata，直接生成有引用答案。
        // 不调用 Generate LLM / ClaimVerifier，避免“7 条 claim 现场枚举计数”产生几十秒延迟和随机重试。
        PatentExactMetadataAnswerer.DirectAnswer direct = PatentExactMetadataAnswerer.tryAnswer(query, evidences);
        if (direct != null) {
            ClaimResult deterministicClaim = ClaimResult.builder()
                    .text(direct.answer())
                    .verdict("SUPPORTED")
                    .evidenceIndex(direct.evidenceIndex())
                    .build();
            log.info("[generateWithClaims][PATENT EXACT_METADATA 确定性回答, skip generate/verify, evidenceIndex={}]",
                    direct.evidenceIndex());
            return GenerationResult.builder()
                    .answer(direct.answer())
                    .claims(List.of(deterministicClaim))
                    .claimFail(false)
                    .build();
        }

        int maxRetry = maxRetry();
        int attempts = 0;
        String feedback = null;
        List<ClaimResult> lastClaims = List.of();
        while (true) {
            String answer = generator.generate(query, evidences, history, feedback);
            if (answer == null) {
                log.warn("[generateWithClaims][回答生成失败(第 {} 次尝试), claimFail=true]", attempts + 1);
                return GenerationResult.builder()
                        .answer(null)
                        .claims(List.of())
                        .claimFail(true)
                        .build();
            }

            List<ClaimResult> claims = verifier.verify(query, answer, evidences, history);
            if (claims == null) {
                attempts++;
                if (attempts > maxRetry) {
                    log.warn("[generateWithClaims][验证解析失败且重试耗尽(尝试 {} 次), 降级信任生成(claimFail=false)]", attempts);
                    return GenerationResult.builder()
                            .answer(answer)
                            .claims(List.of())
                            .claimFail(false)
                            .verificationDegraded(true)
                            .build();
                }
                feedback = "上次回答未能通过证据核查(核查结果无法解析), 请删除可能无据的内容, 只保留证据能支撑的句子, 并重新标注引用。";
                continue;
            }
            if (verifier.allSupported(claims)) {
                log.info("[generateWithClaims][全部断言均有证据支撑, 验证通过(尝试 {} 次)]", attempts + 1);
                return GenerationResult.builder()
                        .answer(answer)
                        .claims(claims)
                        .claimFail(false)
                        .build();
            }

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

    private String buildFeedback(List<ClaimResult> claims) {
        int count = 0;
        List<String> sentences = new ArrayList<>();
        for (ClaimResult claim : claims) {
            if (claim == null || "SUPPORTED".equals(claim.getVerdict())) continue;
            count++;
            if (sentences.size() < UNSUPPORTED_CLAIM_MAX && claim.getText() != null && !claim.getText().isBlank()) {
                sentences.add(claim.getText());
            }
        }
        String detail = sentences.isEmpty() ? "" : " 无据句: " + String.join(" / ", sentences);
        return "上次回答有 " + count + " 句无证据支撑。" + detail
                + " 请删除无据内容, 只保留证据能支撑的句子, 并重新标注引用。";
    }

    private int countUnsupported(List<ClaimResult> claims) {
        int count = 0;
        for (ClaimResult claim : claims) {
            if (claim != null && !"SUPPORTED".equals(claim.getVerdict())) count++;
        }
        return count;
    }

    private int maxRetry() {
        Integer value = properties.getClaim() != null ? properties.getClaim().getMaxRetry() : null;
        return value != null && value >= 0 ? value : DEFAULT_MAX_RETRY;
    }

}
