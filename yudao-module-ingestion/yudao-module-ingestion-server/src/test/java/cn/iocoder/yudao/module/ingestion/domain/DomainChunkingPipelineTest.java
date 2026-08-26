package cn.iocoder.yudao.module.ingestion.domain;

import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.ingestion.split.SplitParams;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DomainChunkingPipelineTest {

    @Test
    void exactDomainPluginWinsAndUnknownDomainFallsBackToGeneral() {
        DomainChunkingPipeline pipeline = new DomainChunkingPipeline(List.of(
                new FakeAdapter("GENERAL", "general"),
                new FakeAdapter("PATENT", "patent")));

        assertEquals("chunking:PATENT", pipeline.pluginFor("patent").pluginId());
        assertEquals("chunking:GENERAL", pipeline.pluginFor("CONTRACT").pluginId());
    }

    @Test
    void executeReturnsPluginIdentityMetadataAndChunksAsTypedResult() {
        DomainChunkingPipeline pipeline = new DomainChunkingPipeline(List.of(
                new FakeAdapter("GENERAL", "general"),
                new FakeAdapter("PATENT", "patent")));
        KnowledgeDocumentRespDTO source = new KnowledgeDocumentRespDTO();
        source.setDomainCode("PATENT");
        source.setKbId(9L);
        source.setTenantId(7L);
        source.setType("PDF");

        DomainChunkingPipeline.Result result = pipeline.execute(
                ParsedDocument.ofText("hello"), SplitParams.of(500), source);

        assertEquals("chunking:PATENT", result.pluginId());
        assertEquals("PATENT", result.domainCode());
        assertEquals("meta:patent", result.domainMetadata());
        assertEquals(List.of("chunk:patent"), result.chunks().stream().map(Chunk::getContent).toList());
    }

    private static class FakeAdapter implements DomainIngestionAdapter {
        private final String domain;
        private final String value;

        private FakeAdapter(String domain, String value) {
            this.domain = domain;
            this.value = value;
        }

        @Override
        public String domainCode() {
            return domain;
        }

        @Override
        public String extractMetadata(ParsedDocument document, KnowledgeDocumentRespDTO source) {
            return "meta:" + value;
        }

        @Override
        public List<Chunk> split(ParsedDocument document, SplitParams params, String domainMetadata) {
            return Chunk.of("chunk:" + value, "TEST");
        }
    }
}
