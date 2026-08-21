package cn.iocoder.yudao.module.ingestion.split;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 切分插件核心逻辑单元测试: 结构切分(标题链注入)/语义切分/工厂自动注册与 auto 判定
 */
class StructureSplitterTest {

    /** 构造含两级标题的测试文档 */
    private ParsedDocument docWithHeadings() {
        ParsedDocument doc = new ParsedDocument();
        ParsedDocument.HeadingElement h1 = new ParsedDocument.HeadingElement("第一章 总则", 1);
        doc.getElements().add(h1);
        ParsedDocument.ParagraphElement p1 = new ParsedDocument.ParagraphElement("本规定适用于公司全体员工的差旅费用报销。");
        doc.getElements().add(p1);
        ParsedDocument.HeadingElement h2 = new ParsedDocument.HeadingElement("1.1 报销标准", 2);
        doc.getElements().add(h2);
        ParsedDocument.ParagraphElement p2 = new ParsedDocument.ParagraphElement("市内交通费实报实销, 上限每日 100 元。");
        doc.getElements().add(p2);
        return doc;
    }

    @Test
    void structureSplit_injectsTitleChain() {
        StructureSplitter splitter = new StructureSplitter();
        List<Chunk> chunks = splitter.split(docWithHeadings(), SplitParams.of(500));
        assertEquals(2, chunks.size(), "两级标题应产出 2 个章节块");
        // 第一章块带标题链前缀
        assertTrue(chunks.get(0).getContent().startsWith("[第一章 总则]"),
                "首块应带标题链前缀, 实际: " + chunks.get(0).getContent());
        assertTrue(chunks.get(0).getContent().contains("差旅费用报销"));
        // 二级标题块带完整标题链
        assertTrue(chunks.get(1).getContent().startsWith("[第一章 总则 > 1.1 报销标准]"),
                "二级块应带完整标题链, 实际: " + chunks.get(1).getContent());
        assertTrue(chunks.get(1).getContent().contains("市内交通费"));
    }

    @Test
    void structureSplit_foldsLongSection() {
        ParsedDocument doc = new ParsedDocument();
        doc.getElements().add(new ParsedDocument.HeadingElement("条款", 1));
        StringBuilder longPara = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longPara.append("这是用于测试超长章节折叠的第").append(i).append("句话, 内容完整语义。");
        }
        doc.getElements().add(new ParsedDocument.ParagraphElement(longPara.toString()));
        StructureSplitter splitter = new StructureSplitter();
        List<Chunk> chunks = splitter.split(doc, SplitParams.of(100));
        assertTrue(chunks.size() > 1, "超长章节应按句子折叠为多块");
        for (Chunk c : chunks) {
            assertTrue(c.getContent().startsWith("[条款]"), "折叠块仍应带标题链前缀");
            assertTrue(SplitUtils.estimateTokens(c.getContent()) <= 120, "折叠块不超限");
        }
    }

    @Test
    void semanticSplit_splitsParagraphs() {
        SemanticSplitter splitter = new SemanticSplitter();
        ParsedDocument doc = ParsedDocument.ofText("第一段内容。\n\n第二段内容, 用于验证段落切分。");
        List<Chunk> chunks = splitter.split(doc, SplitParams.of(500));
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("第一段"));
        assertTrue(chunks.get(1).getContent().contains("第二段"));
    }

    @Test
    void parentChildSplit_linksChildToParent() {
        ParentChildSplitter splitter = new ParentChildSplitter();
        StringBuilder longPara = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longPara.append("父子切分测试第").append(i).append("句。");
        }
        ParsedDocument doc = ParsedDocument.ofText(longPara.toString());
        List<Chunk> chunks = splitter.split(doc, SplitParams.of(100));
        boolean hasChild = chunks.stream().anyMatch(c -> c.getParentId() != null);
        assertTrue(hasChild, "超长父块应产出带 parentId 的子块");
    }

    @Test
    void tableSplit_injectsHeader() {
        TableSplitter splitter = new TableSplitter();
        ParsedDocument doc = new ParsedDocument();
        doc.getElements().add(new ParsedDocument.TableElement(
                List.of("品名", "价格"), List.of(List.of("手机", "4999"), List.of("耳机", "299"))));
        List<Chunk> chunks = splitter.split(doc, SplitParams.of(500));
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("表头: 品名 | 价格"));
        assertTrue(chunks.get(0).getContent().contains("手机"));
        assertEquals("TABLE", chunks.get(0).getChunkType());
    }

    @Test
    void autoSplit_picksStructureWhenHeadingsPresent() {
        SplitterFactory factory = new SplitterFactory(List.of(
                new StructureSplitter(), new SemanticSplitter(), new ParentChildSplitter(),
                new TableSplitter(), new FaqSplitter(), new PolicySplitter()));
        List<Chunk> chunks = factory.getSplitter("auto").split(docWithHeadings(), SplitParams.of(500));
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getContent().startsWith("[第一章 总则]"), "有标题层级时 auto 应走结构切分");
        // 未知 key 同样回退 auto, 不抛异常
        List<Chunk> unknown = factory.getSplitter("not-exist").split(docWithHeadings(), SplitParams.of(500));
        assertEquals(2, unknown.size());
    }

    @Test
    void factory_registersAnnotatedSplittersAndListsStrategies() {
        SplitterFactory factory = new SplitterFactory(List.of(
                new StructureSplitter(), new SemanticSplitter(), new ParentChildSplitter(),
                new TableSplitter(), new FaqSplitter(), new PolicySplitter()));
        assertTrue(factory.isValid("structure"));
        assertTrue(factory.isValid("auto"));
        assertFalse(factory.isValid("not-exist"));
        List<SplitterFactory.StrategyInfo> strategies = factory.listStrategies();
        assertTrue(strategies.stream().anyMatch(s -> "auto".equals(s.key())), "列表应含 auto");
        assertTrue(strategies.stream().anyMatch(s -> "structure".equals(s.key()) && "结构切分".equals(s.name())));
        assertTrue(strategies.stream().anyMatch(s -> "policy".equals(s.key())));
    }

    @Test
    void splitParams_mergeFromJson() {
        SplitParams base = SplitParams.of(500);
        SplitParams merged = SplitParams.merge(base, "{\"maxTokens\":800,\"overlap\":1,\"titleChain\":false}");
        assertEquals(800, merged.getMaxTokens());
        assertEquals(1, merged.getOverlap());
        assertFalse(merged.isTitleChain());
        // 非法 JSON 忽略, 保持默认
        SplitParams bad = SplitParams.merge(base, "{oops");
        assertEquals(500, bad.getMaxTokens());
        // 空串保持默认
        SplitParams empty = SplitParams.merge(base, null);
        assertEquals(500, empty.getMaxTokens());
    }
}
