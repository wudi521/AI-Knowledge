package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatentExactClaimAnswererTest {

    private Evidence claim8() {
        return Evidence.builder()
                .chunkId(88L)
                .content("8. 根据权利要求1至7中任意一项所述的粒子化磁涌装置……")
                .chunkMetadata(JSONUtil.toJsonStr(Map.of(
                        "domainCode", "PATENT",
                        "applicationNo", "202311832214.0",
                        "sectionType", "CLAIMS",
                        "claimNo", 8,
                        "claimType", "DEPENDENT",
                        "dependsOn", List.of(1, 2, 3, 4, 5, 6, 7))))
                .build();
    }

    @Test
    void dependencyQuestionUsesStructuredDependsOn() {
        PatentExactClaimAnswerer.DirectAnswer answer = PatentExactClaimAnswerer.tryAnswer(
                "申请号 202311832214.0 的权利要求8引用了哪些在先权利要求？", List.of(claim8()));

        assertEquals(0, answer.evidenceIndex());
        // P0-06: 保守表述, 只陈述"引用的在先权利要求包括", 不推断"任意一项"语义
        assertTrue(answer.answer().contains("权利要求8引用的在先权利要求包括1、2、3、4、5、6、7"));
        assertTrue(answer.answer().contains("[C1]"));
    }

    @Test
    void rawQuestionReturnsClaimContentVerbatim() {
        PatentExactClaimAnswerer.DirectAnswer answer = PatentExactClaimAnswerer.tryAnswer(
                "申请号 202311832214.0 的权利要求8原文是什么？", List.of(claim8()));

        assertEquals(0, answer.evidenceIndex());
        assertTrue(answer.answer().contains("根据权利要求1至7中任意一项所述的粒子化磁涌装置"));
        assertTrue(answer.answer().contains("[C1]"));
    }

    @Test
    void summaryQuestionStillUsesLlm() {
        PatentExactClaimAnswerer.DirectAnswer answer = PatentExactClaimAnswerer.tryAnswer(
                "申请号 202311832214.0 的权利要求8主要限定了什么？", List.of(claim8()));
        assertNull(answer);
    }
}
