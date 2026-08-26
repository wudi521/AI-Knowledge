package cn.iocoder.yudao.module.ingestion.domain;

import cn.iocoder.yudao.framework.common.plugin.DomainPipelinePlugin;
import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.ingestion.split.SplitParams;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;

import java.util.List;
import java.util.Set;

/**
 * 切片领域插件。
 *
 * <p>这是 Chunking Pipeline 的领域 SPI：核心入库流程只负责编排，领域差异只负责元数据提取和切片实现。
 * GENERAL 作为通用兜底插件；PATENT/CONTRACT/POLICY 等领域插件只声明自己的领域，不修改核心流程。</p>
 */
public interface DomainIngestionAdapter extends DomainPipelinePlugin {

    /** 领域代码，例如 GENERAL/PATENT。 */
    String domainCode();

    @Override
    default String pluginId() {
        return "chunking:" + domainCode();
    }

    @Override
    default Set<String> supportedDomains() {
        return "GENERAL".equalsIgnoreCase(domainCode()) ? Set.of("*") : Set.of(domainCode());
    }

    /** 提取领域文档元数据(JSON 字符串, 持久化到 ai_document.domain_metadata; 无则返回 null)。 */
    String extractMetadata(ParsedDocument document, KnowledgeDocumentRespDTO source);

    /** 领域切分；GENERAL 内部继续使用通用 SplitterFactory。 */
    List<Chunk> split(ParsedDocument document, SplitParams params, String domainMetadata);
}
