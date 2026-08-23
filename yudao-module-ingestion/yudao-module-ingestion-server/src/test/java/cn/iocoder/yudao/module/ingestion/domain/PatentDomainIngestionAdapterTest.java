package cn.iocoder.yudao.module.ingestion.domain;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.ingestion.domain.patent.PatentMetadata;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PatentDomainIngestionAdapterTest {

    @Test
    void claimCountSupportsFullWidthSpacesInPatentHeadings() {
        ParsedDocument doc = document(
                "(21)申请号 202311042981.1\n(54)发明名称 一种代替印花的运动服",
                "权　利　要　求　书 1/1 页",
                "1. 一种代替印花的运动服，其特征在于，采用电脑绣代替印花。\n"
                        + "2. 根据权利要求1所述的运动服，其特征在于，服装染料使用量降低。\n"
                        + "3. 根据权利要求1所述的运动服，其特征在于，进一步限定材料比例。",
                "说　明　书 1/1 页",
                "技术领域\n本发明涉及运动服装技术领域。"
        );
        PatentMetadata metadata = extract(doc);
        assertEquals(3, metadata.getClaimCount());
    }

    @Test
    void claimCountIgnoresCoverPageSummaryLine() {
        ParsedDocument doc = document(
                "(21)申请号 202311042981.1\n权利要求书1页 说明书2页 附图1页",
                "权　利　要　求　书 1/1 页",
                "1. 第一项权利要求。\n2. 第二项权利要求。\n3. 第三项权利要求。",
                "说　明　书 1/2 页",
                "技术领域\n正文"
        );
        PatentMetadata metadata = extract(doc);
        assertEquals(3, metadata.getClaimCount(), "封面页数汇总不能被识别为权利要求章节起点");
    }

    private PatentMetadata extract(ParsedDocument doc) {
        PatentDomainIngestionAdapter adapter = new PatentDomainIngestionAdapter();
        String json = adapter.extractMetadata(doc, new KnowledgeDocumentRespDTO());
        assertNotNull(json);
        return JSONUtil.toBean(json, PatentMetadata.class);
    }

    private ParsedDocument document(String... elements) {
        ParsedDocument doc = new ParsedDocument();
        int page = 1;
        for (String text : elements) {
            ParsedDocument.ParagraphElement element = new ParsedDocument.ParagraphElement(text);
            element.setPage(page++);
            doc.getElements().add(element);
        }
        return doc;
    }
}
