package cn.iocoder.yudao.module.retrieval.service.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专利查询预解析器单测: 确定性提取申请号/公布号/权利要求号及著录字段目标。
 */
class PatentQueryPreParserTest {

    private final PatentQueryPreParser parser = new PatentQueryPreParser();

    @Test
    void parseApplicationNo() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("申请号 202311042981.1 的权利要求1主要限定了什么？");
        assertEquals("202311042981.1", hints.getApplicationNo());
        assertTrue(hints.isClaimIntent());
        assertFalse(hints.isBibliographicIntent(), "申请号仅作为定位条件时不应把问题误判为著录信息查询");
    }

    @Test
    void parsePublicationNo() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("CN 122604134 A 的申请人是谁？");
        assertEquals("CN 122604134 A", hints.getPublicationNo());
        assertTrue(hints.isBibliographicIntent());
        assertEquals(List.of(PatentQueryPreParser.META_APPLICANTS), hints.getMetadataFields());
        assertTrue(hints.hasDeterministicExactMetadata());
    }

    @Test
    void parseClaimCountAsMetadataInsteadOfClaimLookup() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("CN 122621758 A 一共有几项权利要求？");
        assertEquals("CN 122621758 A", hints.getPublicationNo());
        assertTrue(hints.isClaimCountIntent());
        assertFalse(hints.isClaimIntent());
        assertTrue(hints.isBibliographicIntent());
        assertEquals(List.of(PatentQueryPreParser.META_CLAIM_COUNT), hints.getMetadataFields());
        assertTrue(hints.hasDeterministicExactMetadata());
    }

    @Test
    void parseMultipleMetadataFields() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("申请号 202311344028.2 的发明名称和申请人是什么？");
        assertEquals(List.of(PatentQueryPreParser.META_TITLE, PatentQueryPreParser.META_APPLICANTS), hints.getMetadataFields());
        assertTrue(hints.hasDeterministicExactMetadata());
    }

    @Test
    void locatorKeywordDoesNotForceMetadataRoute() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("申请号 202311042981.1 的核心技术方案是什么？");
        assertEquals("202311042981.1", hints.getApplicationNo());
        assertTrue(hints.getMetadataFields().isEmpty());
        assertFalse(hints.isBibliographicIntent());
        assertFalse(hints.hasDeterministicExactMetadata());
    }

    @Test
    void parseSingleClaim() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("权利要求1说了什么？");
        assertEquals(List.of(1), hints.getClaimNos());
        assertEquals(Integer.valueOf(1), hints.getClaimNo());
        assertTrue(hints.isClaimIntent());
    }

    @Test
    void parseClaimRange() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("权利要求8引用了权利要求1至7中的哪些权利要求？");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), hints.getClaimNos());
        assertTrue(hints.isClaimDependencyIntent());
        assertFalse(hints.hasExactClaim());
    }

    @Test
    void parseClaimList() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("权利要求1、3、5的内容是什么？");
        assertEquals(List.of(1, 3, 5), hints.getClaimNos());
    }

    @Test
    void parseClaimOr() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("权利要求1或2的依赖关系");
        assertEquals(List.of(1, 2), hints.getClaimNos());
        assertTrue(hints.isClaimDependencyIntent());
    }

    @Test
    void noIdentifier() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("哪一份文档提出用电脑绣代替印花？");
        assertNull(hints.getApplicationNo());
        assertNull(hints.getPublicationNo());
        assertFalse(hints.hasExactDocumentIdentifier());
        assertFalse(hints.hasExactClaim());
        assertFalse(hints.hasDeterministicExactMetadata());
    }

    @Test
    void parseClaimQueryTypeRawDependencySummary() {
        PatentQueryPreParser.PatentQueryHints raw = parser.parse("申请号 202311042981.1 的权利要求1原文是什么？");
        assertEquals("RAW", raw.getClaimQueryType());

        PatentQueryPreParser.PatentQueryHints dep = parser.parse("申请号 202311832214.0 的权利要求8引用哪些在先权利要求？");
        assertEquals("DEPENDENCY", dep.getClaimQueryType());

        PatentQueryPreParser.PatentQueryHints summary = parser.parse("申请号 202311042981.1 的权利要求1主要限定什么？");
        assertEquals("SUMMARY", summary.getClaimQueryType());
    }
}
