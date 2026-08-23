package cn.iocoder.yudao.module.retrieval.service.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专利查询预解析器单测: 确定性提取申请号/公布号/权利要求号(范围/列表/单个)。
 */
class PatentQueryPreParserTest {

    private final PatentQueryPreParser parser = new PatentQueryPreParser();

    @Test
    void parseApplicationNo() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("申请号 202311042981.1 的权利要求1主要限定了什么？");
        assertEquals("202311042981.1", hints.getApplicationNo());
        assertTrue(hints.isClaimIntent());
        assertTrue(hints.isBibliographicIntent()); // 问题含"申请号"关键词
    }

    @Test
    void parsePublicationNo() {
        PatentQueryPreParser.PatentQueryHints hints = parser.parse("CN 122604134 A 的申请人是谁？");
        assertEquals("CN 122604134 A", hints.getPublicationNo());
        assertTrue(hints.isBibliographicIntent());
        assertTrue(hints.hasExactDocumentIdentifier());
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
        assertFalse(hints.hasExactClaim()); // 无申请号/公布号时不算 EXACT
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
    }
}
