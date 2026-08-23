package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.core.util.StrUtil;
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
    /** P0-07.5 硬上限: 普通 SCOPED_RAG 生成路径 Generate 最多 2 次、Verify 最多 2 次, 达上限不再循环 */
    private static final int MAX_GENERATE = 2;
    private static final int MAX_VERIFY = 2;

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
        long totalStart = System.currentTimeMillis();
        PatentExactMetadataAnswerer.DirectAnswer metadataDirect = PatentExactMetadataAnswerer.tryAnswer(query, evidences);
        if (metadataDirect != null) {
            log.info("[generateWithClaims][PATENT EXACT_METADATA 确定性回答, skip generate/verify, evidenceIndex={}]",
                    metadataDirect.evidenceIndex());
            return deterministic(metadataDirect.answer(), metadataDirect.evidenceIndex());
        }
        // P0-05 fail closed: 明确的著录信息查询但确定性回答失败(缺字段/未命中/未发布/编号冲突) → 拒答, 禁止回退 LLM 编造
        if (PatentExactMetadataAnswerer.isMetadataQuery(query)) {
            log.info("[generateWithClaims][PATENT EXACT_METADATA 确定性回答失败, fail-closed 拒答, 不回退 LLM: query={}]", query);
            return GenerationResult.builder().answer(null).claims(List.of()).claimFail(true).build();
        }

        PatentExactClaimAnswerer.DirectAnswer claimDirect = PatentExactClaimAnswerer.tryAnswer(query, evidences);
        if (claimDirect != null) {
            log.info("[generateWithClaims][PATENT EXACT_CLAIM 依赖/原文确定性回答, skip generate/verify, evidenceIndex={}]",
                    claimDirect.evidenceIndex());
            return deterministic(claimDirect.answer(), claimDirect.evidenceIndex());
        }

        int generateCount = 0;
        int verifyCount = 0;
        long generateMs = 0;
        long verifyMs = 0;
        long repairMs = 0;
        String feedback = null;
        List<ClaimResult> lastClaims = List.of();
        while (generateCount < MAX_GENERATE) {
            long genStart = System.currentTimeMillis();
            String answer = generator.generate(query, evidences, history, feedback);
            generateMs = System.currentTimeMillis() - genStart;
            generateCount++;
            if (answer == null) {
                return GenerationResult.builder().answer(null).claims(List.of()).claimFail(true).build();
            }
            if (verifyCount >= MAX_VERIFY) {
                // Verify 已达硬上限, 不再核查, 降级返回当前生成(回答未完整验证)
                logTiming(query, generateCount, verifyCount, generateMs, verifyMs, repairMs,
                        System.currentTimeMillis() - totalStart, "verify-limit");
                return GenerationResult.builder().answer(answer).claims(List.of()).claimFail(false)
                        .verificationDegraded(true).build();
            }
            long verifyStart = System.currentTimeMillis();
            List<ClaimResult> claims = verifier.verify(query, answer, evidences, history);
            verifyMs = System.currentTimeMillis() - verifyStart;
            verifyCount++;
            if (claims == null) {
                if (generateCount >= MAX_GENERATE) {
                    logTiming(query, generateCount, verifyCount, generateMs, verifyMs, repairMs,
                            System.currentTimeMillis() - totalStart, "verify-unparseable-limit");
                    return GenerationResult.builder().answer(answer).claims(List.of()).claimFail(false)
                            .verificationDegraded(true).build();
                }
                long repairStart = System.currentTimeMillis();
                feedback = "上次回答未能通过证据核查(核查结果无法解析), 请删除可能无据的内容, 只保留证据能支撑的句子, 并重新标注引用。";
                repairMs += System.currentTimeMillis() - repairStart;
                continue;
            }
            if (verifier.allSupported(claims)) {
                logTiming(query, generateCount, verifyCount, generateMs, verifyMs, repairMs,
                        System.currentTimeMillis() - totalStart, "success");
                return GenerationResult.builder().answer(answer).claims(claims).claimFail(false).build();
            }
            lastClaims = claims;
            if (generateCount >= MAX_GENERATE) {
                // 最后一次 generate 仍未全部通过验证 → 保守拒答(claimFail), 禁止继续生成
                logTiming(query, generateCount, verifyCount, generateMs, verifyMs, repairMs,
                        System.currentTimeMillis() - totalStart, "unsupported-limit");
                return GenerationResult.builder().answer(null).claims(lastClaims).claimFail(true).build();
            }
            long repairStart = System.currentTimeMillis();
            feedback = buildFeedback(claims);
            repairMs += System.currentTimeMillis() - repairStart;
        }
        logTiming(query, generateCount, verifyCount, generateMs, verifyMs, repairMs,
                System.currentTimeMillis() - totalStart, "generate-limit");
        return GenerationResult.builder().answer(null).claims(lastClaims).claimFail(true).build();
    }

    /** P0-07.5: 输出单请求 Generate/Verify 次数与各阶段耗时(供性能分析与止血确认) */
    private void logTiming(String query, int generateCount, int verifyCount, long generateMs,
                           long verifyMs, long repairMs, long totalMs, String outcome) {
        log.info("[generateWithClaims][query={} outcome={} generateCount={} verifyCount={} generateMs={} verifyMs={} repairMs={} totalMs={}]",
                StrUtil.maxLength(query, 60), outcome, generateCount, verifyCount, generateMs, verifyMs, repairMs, totalMs);
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
