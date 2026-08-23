package cn.iocoder.yudao.module.ingestion.domain.patent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权利要求解析器单测: 编号识别/跨行合并/从属依赖(range/list/or)。
 */
class PatentClaimParserTest {

    private final PatentClaimParser parser = new PatentClaimParser();

    @Test
    void parseIndependentAndDependentRange() {
        String text = """
                1.一种粒子化磁涌装置，包括壳体。
                2.根据权利要求1所述，还包括磁芯。
                8.根据权利要求1至7中任意一项所述，还包括控制电路。
                """;
        List<PatentClaimParser.PatentClaim> claims = parser.parse(text);
        assertEquals(3, claims.size());
        assertEquals(1, claims.get(0).getClaimNo());
        assertEquals("INDEPENDENT", claims.get(0).getClaimType());
        assertEquals(2, claims.get(1).getClaimNo());
        assertEquals("DEPENDENT", claims.get(1).getClaimType());
        assertEquals(List.of(1), claims.get(1).getDependsOn());
        assertEquals(8, claims.get(2).getClaimNo());
        assertEquals("DEPENDENT", claims.get(2).getClaimType());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), claims.get(2).getDependsOn());
    }

    @Test
    void parseDependentList() {
        String text = """
                1.独立权利要求。
                3.根据权利要求1、2所述的方法，其中还包括步骤。
                """;
        List<PatentClaimParser.PatentClaim> claims = parser.parse(text);
        assertEquals(2, claims.size());
        assertEquals(List.of(1, 2), claims.get(1).getDependsOn());
        assertEquals("DEPENDENT", claims.get(1).getClaimType());
    }

    @Test
    void parseDependentOr() {
        String text = """
                1.独立权利要求。
                2.根据权利要求1或2所述的装置。
                """;
        List<PatentClaimParser.PatentClaim> claims = parser.parse(text);
        assertEquals(2, claims.size());
        assertEquals(List.of(1, 2), claims.get(1).getDependsOn());
        assertEquals("DEPENDENT", claims.get(1).getClaimType());
    }

    @Test
    void preserveFullClaimTextAcrossLines() {
        String text = """
                1.一种分区域视频和图片的储存和下载技术，包括AI对不同区域进行标记，
                储存的方法有若干种，大致原理分为整体储存法和分区域储存法。
                """;
        List<PatentClaimParser.PatentClaim> claims = parser.parse(text);
        assertEquals(1, claims.size());
        assertEquals(1, claims.get(0).getClaimNo());
        assertTrue(claims.get(0).getText().contains("分区域储存法"));
        assertTrue(claims.get(0).getText().contains("AI对不同区域进行标记"));
    }
}
