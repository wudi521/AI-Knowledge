package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatentExactMetadataAnswererTest {

    private Evidence patentEvidence() {
        String metadata = JSONUtil.toJsonStr(Map.ofEntries(
                Map.entry("domainCode", "PATENT"),
                Map.entry("applicationNo", "202311344028.2"),
                Map.entry("publicationNo", "CN 122621758 A"),
                Map.entry("title", "一种分区域视频和图片的储存和下载技术"),
                Map.entry("applicants", List.of("韩信")),
                Map.entry("inventors", List.of("韩信")),
                Map.entry("ipcCodes", List.of("H04N 21/238", "H04N 21/438")),
                Map.entry("claimCount", 7),
                Map.entry("filingDate", "2023-10-17"),
                Map.entry("publicationDate", "2026-08-21"),
                Map.entry("sourceType", "发明专利申请公布")
        ));
        return Evidence.builder()
                .chunkId(1L)
                .documentId("58")
                .documentName("patent.pdf")
                .versionNo("V1")
                .content("任意已发布专利片段")
                .chunkMetadata(metadata)
                .build();
    }

    @Test
    void claimCountUsesStructuredMetadata() {
        PatentExactMetadataAnswerer.DirectAnswer answer = PatentExactMetadataAnswerer.tryAnswer(
                "CN 122621758 A 一共有几项权利要求？", List.of(patentEvidence()));

        assertEquals(0, answer.evidenceIndex());
        assertTrue(answer.answer().contains("共有 7 项权利要求"));
        assertTrue(answer.answer().contains("[C1]"));
    }

    @Test
    void supportsMultipleBibliographicFields() {
        PatentExactMetadataAnswerer.DirectAnswer answer = PatentExactMetadataAnswerer.tryAnswer(
                "申请号 202311344028.2 的发明名称和申请人是什么？", List.of(patentEvidence()));

        assertTrue(answer.answer().contains("发明名称：一种分区域视频和图片的储存和下载技术"));
        assertTrue(answer.answer().contains("申请人：韩信"));
    }

    @Test
    void documentIdentifierUsedOnlyAsLocatorDoesNotTriggerDirectMetadataAnswer() {
        PatentExactMetadataAnswerer.DirectAnswer answer = PatentExactMetadataAnswerer.tryAnswer(
                "申请号 202311344028.2 的核心技术方案是什么？", List.of(patentEvidence()));

        assertNull(answer);
    }

    @Test
    void missingMetadataFieldFailsClosedInsteadOfLlmFallback() {
        // evidence 缺 title → 确定性回答失败, 但该问题属于 metadata 查询, 调用方应 fail closed 拒答而非回退 LLM
        String metadata = JSONUtil.toJsonStr(Map.ofEntries(
                Map.entry("domainCode", "PATENT"),
                Map.entry("applicationNo", "202311344028.2"),
                Map.entry("claimCount", 7)
        ));
        Evidence evidence = Evidence.builder().chunkId(1L).documentId("58")
                .content("x").chunkMetadata(metadata).build();

        assertTrue(PatentExactMetadataAnswerer.isMetadataQuery("申请号 202311344028.2 的发明名称是什么？"));
        assertNull(PatentExactMetadataAnswerer.tryAnswer("申请号 202311344028.2 的发明名称是什么？", List.of(evidence)));
    }

    @Test
    void invalidIdentifierIsMetadataQueryButCannotAnswer() {
        // P0-05 fail closed: 不存在的申请号 + 著录字段 → 是 metadata 查询但无法作答(调用方拒答)
        assertTrue(PatentExactMetadataAnswerer.isMetadataQuery("申请号 999999999999.9 的申请人是谁？"));
        assertNull(PatentExactMetadataAnswerer.tryAnswer("申请号 999999999999.9 的申请人是谁？", List.of()));
    }
}
