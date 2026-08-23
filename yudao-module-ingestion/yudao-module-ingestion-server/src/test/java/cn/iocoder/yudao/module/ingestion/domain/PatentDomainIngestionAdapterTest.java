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
        String text = """
                (21)申请号 202311042981.1
                (54)发明名称 一种代替印花的运动服

                权　利　要　求　书
                1. 一种代替印花的运动服，其特征在于，采用电脑绣代替印花。
                2. 根据权利要求1所述的运动服，其特征在于，服装染料使用量降低。
                3. 根据权利要求1所述的运动服，其特征在于，进一步限定材料比例。

                说　明　书
                技术领域
                本发明涉及运动服装技术领域。
                """;
        PatentDomainIngestionAdapter adapter = new PatentDomainIngestionAdapter();
        String json = adapter.extractMetadata(ParsedDocument.ofText(text), new KnowledgeDocumentRespDTO());

        assertNotNull(json);
        PatentMetadata metadata = JSONUtil.toBean(json, PatentMetadata.class);
        assertEquals(3, metadata.getClaimCount());
    }
}
