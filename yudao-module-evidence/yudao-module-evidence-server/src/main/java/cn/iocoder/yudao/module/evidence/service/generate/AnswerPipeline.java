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

/** 回答生成编排器: 确定性快路径 → LLM 生成 → Claim 验证。 */
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
        PatentExactMetadataAnswerer.DirectAnswer metadataDirect = PatentExactMetadataAnswerer.tryAnswer(query, evidences);
        if (metadataDirect != null) {
            log.info("[generateWithClaims][PATENT EXACT_METADATA 确定性回答, skip generate/verify, evidenceIndex={}]",
                    metadataDirect.evidenceIndex());
            return deterministic(metadataDirect.answer(), metadataDirect.evidenceIndex());
        }

        PatentExactClaimAnswerer.DirectAnswer claimDirect = PatentExactClaimAnswerer.tryAnswer(query, evidences);
        if (claimDirect != null) {
            log.info("[generateWithClaims][PATENT EXACT_CLAIM 依赖关系确定性回答, skip generate/verify, evidenceIndex={}]",
                    claimDirect.evidenceIndex());
            return deterministic(claimDirect.answer(), claimDirect.evidenceIndex());
        }

        int maxRetry = maxRetry();
        int attempts = 0;
        String feedback = null;
        List<ClaimResult> lastClaims = List.of();
        while (true) {
            String answer = generator.generate(query, evidences, history, feedback);
            if (answer == null) {
                return GenerationResult.builder().answer(null).claims(List.of()).claimFail(true).build();
            }

            List<ClaimResult> claims = verifier.verify(query, answer, evidences, history);
            if (claims == null) {
                attempts++;
                if (attempts > maxRetry) {
                    return GenerationResult.builder()
                            .answer(answer).claims(List.of()).claimFail(false).verificationDegraded(true).build();
                }
                feedback = "上次回答未能通过证据核查(核查结果无法解析), 请删除可能无据的内容, 只保留证据能支撑的句子, 并重新标注引用。";
                continue;
            }
            if (verifier.allSupported(claims)) {
                return GenerationResult.builder().answer(answer).claims(claims).claimFail(false).build();
            }

            lastClaims = claims;
            attempts++;
            if (attempts > maxRetry) {
                return GenerationResult.builder().answer(null).claims(lastClaims).claimFail(true).build();
            }
            feedback = buildFeedback(claims);
        }
    }

    private GenerationResult deterministic(String answer, int evidenceIndex) {
        ClaimResult claim = ClaimResult.builder()
                .text(answer)
                .verdict("SUPPORTED")
                .evidenceIndex(evidenceIndex)
                .build();
        return GenerationResult.builder()
                .answer(answer)
                .claims(List.of(claim))
                .claimFail(false)
                .build();
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

    private int maxRetry() {
        Integer value = properties.getClaim() != null ? properties.getClaim().getMaxRetry() : null;
        return value != null && value >= 0 ? value : DEFAULT_MAX_RETRY;
    }
}
